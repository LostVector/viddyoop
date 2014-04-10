package com.rkuo.web;

import com.rkuo.io.Stream;

import java.io.*;
import java.net.HttpURLConnection;
import java.net.MalformedURLException;
import java.net.ProtocolException;
import java.util.Map;

/**
 * Created by IntelliJ IDEA.
 * User: rkuo
 * Date: Sep 9, 2010
 * Time: 1:29:07 PM
 * To change this template use File | Settings | File Templates.
 */
public class WebServices {


    public static String Get(String serviceUrl, Map<String,String> headers) {

        HttpURLConnection conn;
        java.net.URL netUrl;
        String response;
        InputStream is;
        int responseCode;

        try {
            netUrl = new java.net.URL(serviceUrl);
        }
        catch( MalformedURLException muex ) {
            return null;
        }

        try {
            conn = (HttpURLConnection)netUrl.openConnection();
        }
        catch( IOException ioex ) {
            return null;
        }

        try {
            conn.setRequestMethod("GET");
        }
        catch( ProtocolException pex ) {
            return null;
        }

        conn.setDoOutput(true);

        for( Map.Entry<String,String> e : headers.entrySet() ) {
            conn.setRequestProperty( e.getKey(), e.getValue() );
        }

        try {
            responseCode = conn.getResponseCode();
            if( responseCode >= 400 ) {
                 /* error from server */
                is = conn.getErrorStream();
                response = Stream.ToString( is );
                System.out.println(response);
                return null;
            } else {
                is = conn.getInputStream();
            }
        }
        catch( IOException ioex ) {
            return null;
        }

        response = ToString(is);
        return response;
    }

    public static String PostXml( String serviceUrl, String requestXml ) {
        HttpURLConnection   conn;
        java.net.URL        netUrl;
        String              responseXml;
        InputStream is;
        DataOutputStream    out;

        try {
            netUrl = new java.net.URL(serviceUrl);
        }
        catch( MalformedURLException muex ) {
            return null;
        }

        try {
            conn = (HttpURLConnection)netUrl.openConnection();
        }
        catch( IOException ioex ) {
            return null;
        }

        try {
            conn.setRequestMethod("POST");
        }
        catch( ProtocolException pex ) {
            return null;
        }

        conn.setUseCaches(false);
        conn.setDoInput( true );
        conn.setDoOutput( true );
                
        conn.setRequestProperty( "Content-type", "text/xml" );

        try {
            out = new DataOutputStream( conn.getOutputStream() );
            out.writeBytes(requestXml);
            out.flush();
            out.close();
        }
        catch( IOException ioex ) {
            return null;
        }

        try {
            is = conn.getInputStream();
        }
        catch( IOException ioex ) {
            return null;
        }

        responseXml = Stream.ToString( is );
        return responseXml;
    }

    public static String ToString(InputStream is) {
        StringBuilder out = new StringBuilder();
        byte[] b = new byte[4096];

        try {
            while( true ) {
                int i;
                i = is.read(b);
                if( i == -1 ) {
                    break;
                }

                out.append(new String(b, 0, i));
            }
        }
        catch( IOException ioex ) {
            return null;
        }

        return out.toString();
    }
}
