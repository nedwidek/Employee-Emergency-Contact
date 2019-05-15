package dev.obx;

import javax.naming.InitialContext;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.sql.*;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.*;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutorService;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.apache.http.NameValuePair;
import org.apache.http.message.BasicNameValuePair;

import com.twilio.sdk.*;
import com.twilio.sdk.resource.factory.*;
import com.twilio.sdk.resource.instance.*;
import com.twilio.sdk.resource.list.*;
import com.twilio.sdk.verbs.TwiMLResponse;


/**
 *  Servlet providing Twilio callback handler. 
 *  
 *
 *
 *  @author Erik Nedwidek <nedwidek@obx.dev>
 *  @version 1.3
 */
public class EmployeeContact extends HttpServlet {
	
	
	// Twilio account and token.
	// TODO: Update with your Twilio account info.
	static final String ACCOUNT_SID = "";
	static final String AUTH_TOKEN = "";
	
	// Logger
	private static Logger logger = Logger.getLogger("dev.obx.EmployeeContact");
	
	// ExecutorService
	private ExecutorService executor;
	private int CALLBACK_TIMEOUT;
	
	// init() starts ExecutorService.
	public void init() {
		int numThreads;
		
		try {
			numThreads = Integer.parseInt(getInitParameter("threadPoolSize"));
		} catch (Exception e) {
			logger.log(Level.SEVERE, "Exception getting threadPoolSize, proceeding with default value of 5.");
			logger.log(Level.SEVERE, e.getMessage(), e);
			numThreads = 5;
		}
		
		try {
			CALLBACK_TIMEOUT = Integer.parseInt(getInitParameter("threadPoolTaskTimeout"));
		} catch (Exception e) {
			logger.log(Level.SEVERE, "Exception getting threadPoolTaskTimeout, proceeding with default value of 30000.");
			logger.log(Level.SEVERE, e.getMessage(), e);
			CALLBACK_TIMEOUT = 30000;
		}
		
		executor = Executors.newFixedThreadPool(numThreads);
	}
	
	// destroy() shuts down the ExecutorService
	public void destroy() {
		executor.shutdown();
	}
	
    // service() responds to both GET and POST requests.
    // You can also use doGet() or doPost()
    public void service(HttpServletRequest request, HttpServletResponse response) throws IOException {
    	
    	logger.log(Level.INFO, "Handling Twilio Request for Employee Contact System.");
    	
    	response.setContentType("application/xml");
    	
    	// Create our TwiMLResponse for the status message back to the sender.
        TwiMLResponse twiml = new TwiMLResponse();
    	
    	try {
    		
    		// Create the Twilio Rest Client. This will forward the message.
    		TwilioRestClient client = new TwilioRestClient(ACCOUNT_SID, AUTH_TOKEN);
    		
    		InitialContext ctx = new InitialContext();
			String message = null;
    		    	
	    	if(ctx == null) {
	    		throw new Exception("No Context!!");
	    	}
	    	
	    	DataSource ds = (DataSource) ctx.lookup("java:/comp/env/jdbc/postgres");
	    	if(ds == null) {
	    		throw new Exception("DataSource not found!!");
	    	}
	    	
	    	// Read the body and who sent the origianl SMS message.
	    	String from = request.getParameter("From");
	    	String body = request.getParameter("Body");
	    	String twilioNumber = request.getParameter("To");
	    	
	    	logger.log(Level.INFO, "Received request from " + from);
	    	
	        // Get a connection to the database.
	    	try (Connection con=ds.getConnection()) {
	    		// Check to see if the user is authorized to use the system.
	    		boolean goodSender = isAuthorized(con, from);
	    		
	    		// If they are authorized, create the outgoing list and message.
	    		if(goodSender) {
					// This person is authorized to send messages
					
					// Lets see if they are asking for help.
					body = body.trim();
					if(body.equals("?") || body.toLowerCase().equals("help")) {
						returnHelp(con, response, twiml);
						
					} else {	
						com.twilio.sdk.verbs.Message sms = 
							new com.twilio.sdk.verbs.Message("Your message has been received and is being relayed. You will receive another message when the server is done scheduling the messages.");
						twiml.append(sms);
						
						// Write the response.
						response.getWriter().print(twiml.toXML());
						response.flushBuffer();
						MessageThread task = new MessageThread(ds, from, body, twilioNumber);
						executor.submit(task);
					}
					
	    		} else {
					// This person is not authorized to send messages
					com.twilio.sdk.verbs.Message sms = 
						new com.twilio.sdk.verbs.Message("Not authorized.");
					twiml.append(sms);
					
					// Write the response.
					response.getWriter().print(twiml.toXML());
					response.flushBuffer();
				}
	    	} catch(SQLException se) {
	    		throw new Exception(se);
	    	}
	        
    	} catch(Exception e) {
    		if(e.getCause() != null) { e = (Exception) e.getCause(); }
    		logger.log(Level.SEVERE, e.getMessage(), e);
    		StringBuilder errorMessage = new StringBuilder(200);
    		errorMessage.append("Error occurred. Please forward to Erik.\n\nException message:");
    		errorMessage.append(e.getMessage());
    		errorMessage.append("\n\nStack Trace:\n");
    		StringWriter st = new StringWriter();
    		e.printStackTrace(new PrintWriter(st));
    		errorMessage.append(st.toString());
    		try {
    			response.getWriter().print(twiml.toXML());
				response.flushBuffer();
    		} catch (Exception ee) {
    			logger.log(Level.SEVERE, e.getMessage(), e);
    		}
    	}
    }
	
	public boolean isAuthorized(Connection con, String from) throws Exception {
		// Check to see if the user is authorized to use the system.
		try (PreparedStatement st=con.prepareStatement("select count(*) from groups where sender and id in (select group_id from user_group where user_id in (select id from users where mobile = ?))")) {
			st.setString(1, from);
			ResultSet rs=st.executeQuery();
			if(rs.next()) {
				int count = rs.getInt(1);
				if(count == 0) {
					return false;
				} else {
					return true;
				}
			}
		} catch(SQLException se1) {
			throw new Exception(se1);
		}
		// An exception occurred.
		return false;
	}
	
	public void returnHelp(Connection con, HttpServletResponse response, TwiMLResponse twiml) throws Exception {
		HashMap<String, String> groups = new HashMap<String, String>();
		
		try (PreparedStatement st = con.prepareStatement("SELECT code, ad_group FROM groups")) {
			ResultSet rs = st.executeQuery();
			while(rs.next()) {
				String group = rs.getString("ad_group");
				group = group.substring(3, group.indexOf(','));
				groups.put(rs.getString("code"), group);
			}
		} catch(SQLException se1) {
			throw new Exception(se1);
		}
		
		String message = "Messages are sent in the format of &lt;group&gt;: &lt;message&gt;.\n Not specifying the group will send to everyone in the company!\nGroup - Email group\n";
		for(Map.Entry<String, String> entry: groups.entrySet()) {
			message += entry.getKey() + " - " + entry.getValue() + "\n";
		}
		message += "\nExample\nmgr: This message will go to Managers";
		
		com.twilio.sdk.verbs.Message sms = 
			new com.twilio.sdk.verbs.Message(message);
			
		twiml.append(sms);
		
		response.getWriter().print(twiml.toXML());
		response.flushBuffer();
	}
	
	class MessageThread implements Runnable {
		
		private DataSource ds;
		private String from;
		private String body;
		private String twilioNumber;
		
		public MessageThread(DataSource ds, String from, String body, String twilioNumber) {
			this.ds = ds;
			this.from = from;
			this.body = body;
			this.twilioNumber = twilioNumber;
		}
		
		public void run() {
			
			EmployeeContact.logger.log(Level.INFO, "New message submitted from " + from);
			
			TwilioRestClient client = new TwilioRestClient(EmployeeContact.ACCOUNT_SID, EmployeeContact.AUTH_TOKEN);
			ArrayList<String> to = null;
    		String message = null;
    		int numRecipients = 0;
			
			try (Connection con=ds.getConnection()) {
				InitialContext ctx = new InitialContext();
				
				// TODO: Update with your own name
				StringBuilder tmpMessage = new StringBuilder("Message from XXXXXXXXX (");
				String firstName = "Jane", lastName = "Doe", tag = "all";
				try (PreparedStatement st=con.prepareStatement("select firstname, lastname from users where mobile = ?")) {
					st.setString(1, from);
					ResultSet rs = st.executeQuery();
					if(rs.next()) {
						firstName = rs.getString("firstname");
						lastName = rs.getString("lastname");
					}
				} catch(SQLException se1) {
					throw new Exception(se1);
				}
				tmpMessage.append(firstName);
				tmpMessage.append(" ");
				tmpMessage.append(lastName);
				tmpMessage.append(")\n\n");
				
				int tagOffset = body.indexOf(':');
				if(tagOffset > 0 && tagOffset < 10) {
					tag = body.substring(0, tagOffset).toLowerCase().trim();
					tmpMessage.append(body.substring(tagOffset + 1).trim());
				} else {
					tmpMessage.append(body);
				}
				
				tmpMessage.append("\n\nThis is an automatically generated message. Please do not respond.");
				
				message = tmpMessage.toString();
				
				try (PreparedStatement st = con.prepareStatement("SELECT mobile FROM users WHERE id in (SELECT user_id FROM user_group where group_id in (SELECT id FROM groups WHERE code = ?))")) {
					st.setString(1, tag);
					ResultSet rs = st.executeQuery();
					to = new ArrayList<String>();
					while(rs.next()) {
						numRecipients++;
						to.add(rs.getString("mobile"));
					}
				} catch(SQLException se1) {
					throw new Exception(se1);
				}
				
				if(to != null) {
					// Create our message factory.
					MessageFactory factory = client.getAccount().getMessageFactory();
					
					int numErrors = 0;
					// Add each message to send
					for(String mobile: to) {
						if(mobile.equals(from)) {
							// The original sender only gets a status message.
							numRecipients--;
						} else {
							List<NameValuePair> params = new ArrayList<NameValuePair>();
							params.add(new BasicNameValuePair("To", mobile));
							params.add(new BasicNameValuePair("From", twilioNumber));
							params.add(new BasicNameValuePair("Body", message));
							try {
								Message sms = factory.create(params);
							} catch (Exception ee) {
								logger.log(Level.SEVERE, ee.getMessage(), ee);
								numErrors++;
							}
						}
					}
					
					// send the status sms back to the original sender.
					List<NameValuePair> params = new ArrayList<NameValuePair>();
					params.add(new BasicNameValuePair("To", from));
					params.add(new BasicNameValuePair("From", twilioNumber));
					params.add(new BasicNameValuePair("Body", "Sent the following message to " + numRecipients + " users. (" + numErrors + " Errors)\n\n" + message));
					Message sms = factory.create(params);
				}
				
			} catch(Exception e) {
				if(e.getCause() != null) { e = (Exception) e.getCause(); }
				logger.log(Level.SEVERE, e.getMessage(), e);
			}
			
		}
		
	}
}
