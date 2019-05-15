import javax.naming.Context;
import javax.naming.NamingEnumeration;
import javax.naming.directory.*;
import java.util.*;
import java.sql.*;
import java.io.*;

/**
 * Quick and dirty, but it works. Search for strings with <[^>]+> in them such as <DB User>. These need to 
 * be updated for your environment.
*/
public class AD_Loader {

	static DirContext ldapContext;
	static StringBuilder output = new StringBuilder(1024);
	static int nonFatalErrors = 0;
	
	public static void main(String[] args) {
		
		Connection db = null;
		HashMap<String, Integer> groups = new HashMap<String, Integer>(10);
		ArrayList<String> adUsers = new ArrayList<String>(140);
		
		// Load the Postgres JDBC driver
		try {
			log("Loading PostgreSQL Driver");
			Class.forName("org.postgresql.Driver");
		} catch(ClassNotFoundException cnfe) {
			exit(-1, cnfe);
		}
		
		// Get a connection
		try {
			log("Connecting to Database");
			db = DriverManager.getConnection("jdbc:postgresql:<your PostgreSQL DB", "<DB User>", "<Password>");
		} catch(SQLException se) {
			exit(-2, se);
		}
		
		// Load groups from database
		try (Statement st = db.createStatement()) {
			ResultSet rs = st.executeQuery("SELECT id, ad_group FROM groups");
			while(rs.next()) {
				Integer id = new Integer(rs.getInt("id"));
				String name = rs.getString("ad_group");
				log("Group: (" + id + ") " + name);
				groups.put(name, id);
			}
		} catch(SQLException se) {
			exit(-3, se);
		}
		
		// Connect to the LDAP directory
		try {
			log("Test Active Directory");
			Hashtable<String, String> ldapEnv = new Hashtable<String, String>(11);
			ldapEnv.put(Context.INITIAL_CONTEXT_FACTORY, "com.sun.jndi.ldap.LdapCtxFactory");
			ldapEnv.put(Context.PROVIDER_URL, "ldap://<your DC>:389");
			ldapEnv.put(Context.SECURITY_AUTHENTICATION, "simple");
			ldapEnv.put(Context.SECURITY_PRINCIPAL, "<DN for AD User>");
			ldapEnv.put(Context.SECURITY_CREDENTIALS, "<Password for AD User>");
			
			ldapContext = new InitialDirContext(ldapEnv);
		} catch(Exception e) {
			exit(-4, e);
		}
		
		// Search AD and upsert users into database
		try {
			
			SearchControls search = new SearchControls();
			
			String attributes[] = {"sn", "givenName", "samAccountName", "mobile", "memberOf"};
			String searchBase = "<Your Search Base>";
			String searchFilter = "(&(objectClass=user)(objectCategory=person)(!(userAccountControl:1.2.840.113556.1.4.803:=2)))";
			
			search.setReturningAttributes(attributes);
			search.setSearchScope(SearchControls.SUBTREE_SCOPE);;
			
			int userCount = 0;
			
			NamingEnumeration<SearchResult> queryResult = ldapContext.search(searchBase, searchFilter, search);
			
			while(queryResult.hasMoreElements()) {
				userCount++;
				
				SearchResult user = (SearchResult)queryResult.next();
				Attributes attrs = user.getAttributes();
				String username = attrs.get("samAccountName").get().toString();
				adUsers.add(username);
				String firstname = (attrs.get("givenName") != null ? attrs.get("givenName").get().toString() : "");
				String lastname = (attrs.get("sn") != null ? attrs.get("sn").get().toString() : "");
				String mobile = (attrs.get("mobile") != null ? attrs.get("mobile").get().toString() : "");
				if(!mobile.equals("")) {
					mobile = "+1" + mobile.replaceAll("[- ()]", "");
				}
				
				log("------------------------------------------\n>> " + user.getName());
				log(">> " + username);
				log(">> " + firstname + " " + lastname);
				log(">> " + mobile);
				log(">> Member Of:");
				Attribute memberOf = attrs.get("memberOf");
				if(memberOf != null && memberOf.size() > 0) {
					for(int i=0; i<memberOf.size(); i++) {
						log("      " + memberOf.get(i));
					}
				}
				
				// Remove the user if they do not have a mobile phone number.
				if(mobile.equals("")) {
					try (PreparedStatement st = db.prepareStatement("DELETE FROM users WHERE username = ?")) {
						st.setString(1, username);
						st.execute();
					} catch (SQLException se) {
						log(se);
					}
				} else {
					// Attempt an upsert. don't worry about concurrency.
					try (PreparedStatement st = db.prepareStatement("UPDATE users SET firstname = ?, lastname = ?, mobile = ? WHERE username = ?")) {
						st.setString(1, firstname);
						st.setString(2, lastname);
						st.setString(3, mobile);
						st.setString(4, username);
						int numUpdated = st.executeUpdate();
						if(numUpdated == 0) {
							try (PreparedStatement st2 = db.prepareStatement("INSERT INTO users(username, firstname, lastname, mobile) VALUES (?, ?, ?, ?)")) {
								st2.setString(1, username);
								st2.setString(2, firstname);
								st2.setString(3, lastname);
								st2.setString(4, mobile);
								st2.execute();
							} catch(SQLException se2) {
								log(se2);
							}
									
						}
					} catch(SQLException se) {
						log(se);
					}
					
					// Select the user.
					int userId = -1;
					try (PreparedStatement st = db.prepareStatement("SELECT id FROM users WHERE username = ?")) {
						st.setString(1, username);
						ResultSet rs = st.executeQuery();
						if(rs.next()) { userId = rs.getInt("id"); }
					} catch (SQLException se) {
						log(se);
					}
					
					// Process group memberships.
					if(userId != -1) {
						// First delete the group memberships
						try (PreparedStatement st = db.prepareStatement("DELETE FROM user_group WHERE user_id = ?")) {
							st.setInt(1, userId);
							st.execute();
						} catch (SQLException se) {
							log(se);
						}
						// Now add any groups in a batch.
						if(memberOf != null && memberOf.size() > 0) {
							try (PreparedStatement st = db.prepareStatement("INSERT INTO user_group(user_id, group_id) VALUES (?, ?)")) {
								for(int i=0; i< memberOf.size(); i++) {
									String groupName = memberOf.get(i).toString();
									if(groups.containsKey(groupName)) {
										int groupId = groups.get(groupName).intValue();
										st.setInt(1, userId);
										st.setInt(2, groupId);
										st.addBatch();
									}
								}
								st.executeBatch();
							} catch (SQLException se) {
								log(se);
							}
						}
					}
				}
				
			}
			
			log("------------------------------------------\n\n Total Users: " + userCount);
			
		} catch (Exception e) {
			exit(-100, e);
		}
		
		// Now delete any obsolete users.
		ArrayList<String> deleteUsers = new ArrayList<String>();
		try (Statement st = db.createStatement()) {
			ResultSet rs = st.executeQuery("SELECT username FROM users");
			while(rs.next()) {
				String username = rs.getString("username");
				if(!adUsers.contains(username)) { deleteUsers.add(username); }
			}
			
		} catch(SQLException se) {
			exit(-200, se);
		}
		
		try (PreparedStatement st = db.prepareStatement("DELETE FROM users WHERE username = ?")) {
			for(String username: deleteUsers) {
				st.setString(1, username);
				st.addBatch();
			}
			st.executeBatch();
			
		} catch(SQLException se) {
			exit(-200, se);
		}

		exit(0);
	}
	
	public static void log(String message) {
		output.append(message);
		output.append("\n");
	}
	
	public static void log(String message, boolean isError) {
		if(isError) { nonFatalErrors++; }
		log(message);
	}
	
	public static void log(Throwable t) {
		log(t.getMessage());
		StringWriter sw = new StringWriter();
		t.printStackTrace(new PrintWriter(sw));
		log(sw.toString());
		nonFatalErrors++;
	}
	
	public static void exit(int code, Throwable t, boolean showOutput) {
		if(t != null) {
			log(t.getMessage());
			StringWriter sw = new StringWriter();
			t.printStackTrace(new PrintWriter(sw));
			log(sw.toString());
		}
		if(nonFatalErrors > 0) { log("Total non-fatal errors: " + nonFatalErrors); }
		if(showOutput || nonFatalErrors > 0) { System.out.println(output.toString()); }
		System.exit(code);
	}
	
	public static void exit(int code, Throwable t) {
		exit(code, t, true);
	}
	
	public static void exit(int code, boolean showOutput) {
		exit(code, null, showOutput);
	}
	
	public static void exit(int code) {
		exit(code, null, (code < 0 ? true : false));
	}
}
