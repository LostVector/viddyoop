package com.rkuo.mysql;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;

// you must link any class using this with the mysql connector jars
public abstract class DBManager {

    public static boolean Initialize() {

        //Register the JDBC driver for MySQL.
        try {
            Class.forName("com.mysql.jdbc.Driver");
        }
        catch( ClassNotFoundException cnfex ) {
            return false;
        }

        return true;
    }

    public Connection GetConnection() {

        Connection  c;

        try {
            c = DriverManager.getConnection( getLimitedUrl(), getLimitedUsername(), getLimitedPassword() );
        }
        catch( SQLException sqlex ) {
            return null;
        }

        return c;
    }

    public Connection GetRootConnection() {

        Connection  c;

        try {
            c = DriverManager.getConnection( getRootUrl(), getRootUsername(), getRootPassword() );
        }
        catch( SQLException sqlex ) {
            return null;
        }

        return c;
    }

    public abstract String getLimitedDBName();

    public abstract String getLimitedUrl();
    public abstract String getLimitedUsername();
    public abstract String getLimitedPassword();

    public abstract String getRootUrl();
    public abstract String getRootUsername();
    public abstract String getRootPassword();
}
