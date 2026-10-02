package com.rkuo.hadoop;

import junit.framework.Assert;
import junit.framework.TestCase;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

import java.util.Map;

@RunWith(JUnit4.class)
public class TestHBXJobPreprocessor {

    @Test
    public void testLoadSettings() {
        HBXJobPreprocessor p = new HBXJobPreprocessor();
        Map<String,String> mapSettings = p.LoadSettings("/Users/root/Downloads/hbxtest/preprocessor.xml");
        Assert.assertTrue(mapSettings.size() > 0);
    }
}
