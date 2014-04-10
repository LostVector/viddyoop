package com.rkuo.mysql;

import java.sql.Connection;
import java.sql.Statement;

/**
 * Created by IntelliJ IDEA.
 * User: rkuo
 * Date: Sep 11, 2010
 * Time: 10:06:08 PM
 * To change this template use File | Settings | File Templates.
 */
public abstract class DBSetup {

    protected DBManager _dbm = null;

    public DBSetup() {
        DBManager.Initialize();
        return;
    }

    public DBSetup( DBManager dbm ) {
        _dbm = dbm;
        return;
    }

    public boolean InitializeDB() {

        boolean br;

        br = this.UninitializeDB();

        try {
            Connection c;
            Statement stmt;

            c = _dbm.GetRootConnection();
            if( c == null ) {
                return false;
            }

            //Get a Statement object
            stmt = c.createStatement();
            if( stmt == null ) {
                return false;
            }

            //Create the new database
            stmt.executeUpdate( "CREATE DATABASE " + _dbm.getLimitedDBName() );

            //Register a new user named auser on the
            // database named JunkDB with a password
            // drowssap enabling several different
            // privileges.
            stmt.executeUpdate(
                    "GRANT SELECT,INSERT,UPDATE,DELETE," +
                            "CREATE,DROP " +
                            "ON " + _dbm.getLimitedDBName() + ".* TO '" + _dbm.getLimitedUsername() + "'@'localhost' " +
                            "IDENTIFIED BY '" + _dbm.getLimitedPassword() + "';");
            c.close();

            // open the limited connection and initialize the database
            c = _dbm.GetConnection();
            br = InitializeTables( c );
            if( br == false ) {
                return false;
            }
        }
        catch( Exception e ) {
            e.printStackTrace();
            return false;
        }//end catch

        return true;
    }

    public boolean UninitializeDB() {

        Connection c;

        c = _dbm.GetRootConnection();
        if( c == null ) {
            return false;
        }

        try {
            Statement stmt;

            stmt = c.createStatement();

            //Remove the user named auser
            stmt.executeUpdate( "REVOKE ALL PRIVILEGES ON *.* FROM '" + _dbm.getLimitedUsername() + "'@'localhost';" );
            stmt.executeUpdate( "REVOKE GRANT OPTION ON *.* FROM '" + _dbm.getLimitedUsername() + "'@'localhost';" );
            stmt.executeUpdate( "DELETE FROM mysql.user WHERE User='" + _dbm.getLimitedUsername() + "' and Host='localhost';" );
            stmt.executeUpdate( "FLUSH PRIVILEGES;" );

            //Delete the database
            stmt.executeUpdate( "DROP DATABASE "+ _dbm.getLimitedDBName() + ";" );

            c.close();
        }
        catch (Exception e) {
            e.printStackTrace();
        }//end catch

        return true;
    }

    public abstract boolean InitializeTables( Connection c );
}
