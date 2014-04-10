package com.rkuo.hadoop;

/**
 * Created by IntelliJ IDEA.
 * User: rkuo
 * Date: 6/16/12
 * Time: 12:01 PM
 * To change this template use File | Settings | File Templates.
 */
public class AC3ConversionResult {

    public static int AC3CONVERSION_OK = 0;
    public static int AC3CONVERSION_UNNECESSARY = -1;
    public static int AC3CONVERSION_HUNG = -2;
    public static int AC3CONVERSION_FAILED = -3;

    int returnCode;
    String convertedFile;

    public AC3ConversionResult() {
        returnCode = 1;
        convertedFile = null;
    }
}
