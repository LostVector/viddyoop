viddyoop
======

Distributed video analysis and transcoding system leveraging Hadoop

======

The MIT License (MIT)

Copyright (c) 2014 Richard Kuo

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in
all copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
THE SOFTWARE.

======

Developer/Build requirements
- JDK 17 or later
- Maven 3.9 or later

To build:

- Run `mvn package` from the repository root.
- The runnable fat jars are written to each module's `target/` directory:
  - `java/modules/HBXFileBrokerExe/target/HBXFileBroker.jar`
  - `java/modules/HBXFileCollectorExe/target/HBXFileCollector.jar`
  - `java/modules/HBXJobPreprocessor/target/HBXJobPreprocessor.jar`
  - `java/modules/HBXMapReduce/target/HBXMapReduce.jar`
  - `java/modules/HBXJobSubmitterExe/target/HBXJobSubmitter.jar`
- Sample launch scripts and configuration files live under
  `java/modules/HBXJobSubmitterExe/resources/`.


The iTunes preprocessor plugin relies on the following binaries
- HandBrakeCLI (for scanning the input file)
- mkvinfo (for scanning the input file)
- aften (for AC3 encoding)
- libdca (for DTS decoding)
- mkvmerge (for repairing MKV files)
- mkvextract (for extracting track information)

The iTunes mapreduce plugin relies on the following binaries:
- HandBrakeCLI (for scanning the input file and encoding it)
- mkvinfo (for scanning the input file)
- mkvextract (for extracting subtitles and other track information)
