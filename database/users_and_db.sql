create user emergency_user with password '<password>'; -- Used by the loader program needs select, insert, delete, update
create user emergency_tomcat with password '<password>'; -- Tomcat DB User will be given only select priv
create database emergency owner emergency_user;