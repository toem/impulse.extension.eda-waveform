This folder contains the FST (Fast Signal Trace) reader implementation, documentation, and sample data for validation inside the impulse framework.

### Contents

- FstReader.java
	- FST reader implementation that parses FST blocks (header, geometry, hierarchy, value changes) and writes impulse records.
- fst-reader.md
	- Reader documentation: capabilities, properties, implementation details, limitations.
- fst-format.md
	- Notes and specification details about the FST file format and block structures.
- samples/
	- Example inputs and reference outputs for verification.
	- OVERVIEW.md: Summary of the sample collection.
	- ref_build.imp: Reference build/test configuration.
	- t0001.fst … t0030.fst: Input FST files covering features and edge cases.
	- tXXXX.recMl files: Expected impulse record outputs.
	- Variants (e.g., range/select/transform): Expected outputs for specific property configurations.
