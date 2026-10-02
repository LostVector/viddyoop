package com.rkuo.util;

import java.util.HashMap;

/**
 * Created by IntelliJ IDEA.
 * User: rkuo
 * Date: Apr 17, 2009
 * Time: 11:06:40 PM
 * To change this template use File | Settings | File Templates.
 */
public class CommandLineParser {

    private HashMap<String,String> _map;

    public CommandLineParser() {
        _map = new HashMap<String,String>();
        return;
    }

    public boolean Parse( String[] args ) {

        for( String arg : args ) {
            String  key, value;
            int     p;

            // All arguments must start with a forward slash
            p = arg.indexOf( '/' );
            if( p != 0 ) {
                continue;
            }

            // All arguments must have a colon delimiting the key from the value.
            p = arg.indexOf( ':' );
            if( p == -1 ) {
                continue;
            }

            if( p == 1 ) {
                continue;
            }

            key = arg.substring( 1, p ).toLowerCase();
            value = arg.substring( p+1 );

            _map.put( key, value );
        }

        return true;
    }

    public boolean Contains( String name ) {

        String  lowerName;

        lowerName = name.toLowerCase();
        if( _map.containsKey(lowerName) == true ) {
            return true;
        }

        return false;
    }

    public boolean GetBoolean( String name ) {
        String  lowerName;
        String  value;

        lowerName = name.toLowerCase();
        if( _map.containsKey(lowerName) == false ) {
            return false;
        }

        value = _map.get(lowerName);
        if( value.compareToIgnoreCase( "true" ) == 0 ) {
            return true;
        }

        return false;
    }

    public int GetInteger( String name ) {
        String  lowerName;
        String  value;
        int     x;

        lowerName = name.toLowerCase();
        if( _map.containsKey(lowerName) == false ) {
            return 0;
        }

        value = _map.get(lowerName);
        x = Integer.parseInt( value );

        return x;
    }

    public long GetLong( String name ) {
        String  lowerName;
        String  value;
        long     x;

        lowerName = name.toLowerCase();
        if( _map.containsKey(lowerName) == false ) {
            return 0;
        }

        value = _map.get(lowerName);
        x = Long.parseLong( value );

        return x;
    }

    public String GetString( String name ) {
        String  lowerName;
        String  value;

        lowerName = name.toLowerCase();
        if( _map.containsKey(lowerName) == false ) {
            return "";
        }

        value = _map.get(lowerName);

        return value;
    }
}
