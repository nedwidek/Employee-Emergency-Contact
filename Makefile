CC=javac

CP=$(CLASSPATH):/usr/share/java/servlet.jar:deployment/WEB-INF/lib/twilio.jar:/usr/share/java/servlet.jar:/usr/share/java/postgresql-jdbc3.jar
CFLAGS=-d deployment/WEB-INF/classes/ -cp $(CP)
SOURCEDIR=development/WEB-INF/classes/com/vrobx
SOURCES=$(SOURCEDIR)/EmployeeContact.java

CONTEXT=/var/lib/tomcat/webapps/eecontact/

.SUFFIXES: .java .class

.java.class:
	$(CC) $(CFLAGS) $*.java

default: sources cronjob

cronjob:
	cd database && $(CC) -cp $(CP) AD_Loader.java

sources: $(SOURCES:.java=.class)

deploy: default
	sudo ./deploy.sh $(CONTEXT)
	sudo ./cronjob.sh
	sudo service tomcat restart
