package com.rkuo.ffmpeg;

import com.rkuo.util.ExecuteProcessCallback;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class FrameCountCallback implements ExecuteProcessCallback {

    protected Pattern p;
    protected Long frames;

    public FrameCountCallback() {
        String pattern = "frame= *(\\d+) +fps=";
        p = Pattern.compile(pattern);
        frames = null;
    }

    @Override
    public void ProcessLine(String line) {
        Matcher m = p.matcher(line);
        boolean br;

        br = m.find();
        if( br == true ) {
            frames = Long.parseLong(m.group(1));
        }
    }

    public Long getFrames() {
        return frames;
    }
}