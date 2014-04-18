package com.rkuo.handbrake;

import com.rkuo.threading.RKEvent;

import java.util.ArrayList;
import java.util.List;

/**
 * Created with IntelliJ IDEA.
 * User: rkuo
 * Date: 4/10/14
 * Time: 3:23 PM
 * To change this template use File | Settings | File Templates.
 */
public class HandBrakeExeParams {

    // general
    public Boolean Help;
    public Boolean Update;
    public Integer Verbose; // 0 thru 3 are valid. 0 is none, 1 is normal, 2 is memory related, 3 is packet by packet
    public String Preset;
    public Boolean PresetList;

    // source
    public String Input;
    public Boolean Scan;

    // destination
    public String Output;
    public DestinationFormat Format;
    public Boolean Markers;
    public Boolean LargeFile;
    public Boolean Optimize;
    public Boolean IpodAtom;

    // video
    public VideoEncoderOption Encoder;
    public Double Quality;
    public VideoRateOption Rate;
    public String EncodingOptions;

    // audio
    public List<Integer> AudioTracks;
    public List<AudioEncoderOption> AudioEncoders;
    public List<AudioMixdownOption> AudioMixdowns;
    public List<Integer> AudioSampleRates;
    public List<Integer> AudioBitrates;
    public List<Double> AudioDynamicRangeCompressions;

    // picture
    public VideoFrameRateControlOption RateControl;
    public Integer Width;
    public Integer Height;
    public Integer MaxWidth;
    public Integer MaxHeight;
    public PictureCropOption Crop;
    public Integer CropTop;
    public Integer CropBottom;
    public Integer CropLeft;
    public Integer CropRight;
    public Integer LooseCropMaximum;
    public PictureAnamorphicOption Anamorphic;
    public Integer Modulus;

    // filters
    public Boolean Decomb;
    public Boolean Detelecine;

    // subtitle
    public List<String> SrtFiles;
    public List<String> SrtCodesets;
    public List<String> SrtLanguages;
    public Integer SrtDefault;

    // helper specific options
    public String Executable;
    public Long Timeout;
    public IHandBrakeExeCallback Callback;
    public RKEvent Abort;

    // does not fully encompass all handbrake options yet
    public HandBrakeExeParams() {
        Help = false;
        Update = false;
        Verbose = 0;
        Preset = "";
        PresetList = false;

        Input = "";
        Scan = false;

        Output = "";
        Format = DestinationFormat.AUTODETECT;
        Markers = false;
        LargeFile = false;
        Optimize = false;
        IpodAtom = false;


        Quality = 20.0;
        Rate = VideoRateOption.FRAMERATE_VARIABLE;
        RateControl = VideoFrameRateControlOption.DEFAULT;
        Encoder = VideoEncoderOption.DEFAULT;
        EncodingOptions = "";

        AudioTracks = new ArrayList<Integer>();
        AudioEncoders = new ArrayList<AudioEncoderOption>();
        AudioMixdowns = new ArrayList<AudioMixdownOption>();
        AudioSampleRates = new ArrayList<Integer>(); // zeroes here will be treated as "auto"
        AudioBitrates = new ArrayList<Integer>(); // zeroes here will be treated as "auto"
        AudioDynamicRangeCompressions = new ArrayList<Double>();

        SrtFiles = new ArrayList<String>();
        SrtCodesets = new ArrayList<String>();
        SrtLanguages = new ArrayList<String>();
        SrtDefault = 0;

        // Filters
        Decomb = false;
        Detelecine = false;

        // Picture
        Width = 0;
        Height = 0;
        MaxHeight = 0;
        MaxWidth = 0;
        Crop = PictureCropOption.Auto;
        CropTop = 0;
        CropBottom = 0;
        CropLeft = 0;
        CropRight = 0;
        LooseCropMaximum = 0;
        Anamorphic = PictureAnamorphicOption.AUTO;
        Modulus = 0;

        Callback = null;
        Abort = null;
        Timeout = 15L * 60L * 1000L;
        return;
    }

    public enum DestinationFormat {
        AUTODETECT("auto"),
        MP4("mp4"),
        MKV("mkv");

        private final String option;

        DestinationFormat(String option) {
            this.option = option;
        }

        public String toString() {
            return this.option;
        }
    }

    public enum VideoEncoderOption {
        DEFAULT("default"),
        X264("x264"),
        FFMPEG4("ffmpeg4"),
        FFMPEG2("ffmpeg2"),
        THEORA("theora");

        private final String option;

        VideoEncoderOption(String option) {
            this.option = option;
        }

        public String toString() {
            return this.option;
        }
    }

    public enum VideoFrameRateControlOption {
        DEFAULT("Default"),
        VARIABLE("vfr"),
        CONSTANT("cfr"),
        PEAK_LIMITED("pfr");

        private final String option;

        VideoFrameRateControlOption(String option) {
            this.option = option;
        }

        public String toString() {
            return this.option;
        }
    }

    public enum VideoRateOption {
        FRAMERATE_VARIABLE("variable"),
        FRAMERATE_5("5"),
        FRAMERATE_10("10"),
        FRAMERATE_12("12"),
        FRAMERATE_15("15"),
        FRAMERATE_23_976("23.976"),
        FRAMERATE_24("24"),
        FRAMERATE_25("25"),
        FRAMERATE_29_97("29.97"),
        FRAMERATE_30("30"),
        FRAMERATE_50("50"),
        FRAMERATE_59_94("59.94"),
        FRAMERATE_60("60");

        private final String option;

        VideoRateOption(String option) {
            this.option = option;
        }

        public String toString() {
            return this.option;
        }
    }

    public enum PictureAnamorphicOption {
        AUTO,
        STRICT,
        LOOSE,
        CUSTOM
    }

    public enum AudioEncoderOption {
        CA_AAC("ca_aac"),
        CA_HAAC("ca_haac"),
        FAAC("faac"),
        FFAAC("ffaac"),
        COPY_AAC("copy:aac"),
        FFAC3("ffac3"),
        COPY_AC3("copy:ac3"),
        COPY_DTS("copy:dts"),
        COPY_DTSHD("copy:dtshd"),
        LAME("lame"),
        COPY_MP3("copy:mp3"),
        VORBIS("vorbis"),
        FFFLAC("ffflac"),
        FFFLAC24("ffflac24"),
        COPY("copy");

        private final String option;

        AudioEncoderOption(String option) {
            this.option = option;
        }

        public String toString() {
            return this.option;
        }
    }

    public enum AudioMixdownOption {
        AUTO("auto"),
        MONO("mono"),
        LEFT_ONLY("left_only"),
        RIGHT_ONLY("right_only"),
        STEREO("stereo"),
        DPL1("dpl1"),
        DPL2("dpl2"),
        FIVE_POINT_ONE("5point1"),
        SIX_POINT_ONE("6point1"),
        SEVEN_POINT_ONE("7point1"),
        FIVE_TWO_LFE("5_2_lfe");

        private final String option;

        AudioMixdownOption(String option) {
            this.option = option;
        }

        public String toString() {
            return this.option;
        }
    }

    public enum PictureCropOption {
        Auto,
        Strict,
        Loose
    }
}
