<!---
title: "FST (Fast Signal Trace) Reader"
author: "Thomas Haber"
keywords: [FST, Fast Signal Trace, impulse, EDA, waveform, simulation, signal analysis, digital, parser, extension, compressed, block-compressed]
description: "The FST Reader extension for impulse enables efficient import, filtering, and analysis of digital simulation waveforms in the FST format. Supports advanced configuration, hierarchical browsing, block-compressed data, and seamless integration with impulse's visualization and processing tools. Experimental status: subject to change."
category: "impulse-extension"
tags:
  - reference
  - serializer
docID: xxx
--->
# FST (Fast Signal Trace) Reader

> ⚠️ Important: Experimental
> The FST Reader is currently in an experimental state. Features, performance, and format coverage may change, and breaking changes can occur. Use for evaluation and testing only; not yet recommended for production workflows.

The FST Reader lets you import and analyze digital simulation waveforms stored in the FST (Fast Signal Trace) binary format inside the impulse framework. It bridges compact, block-compressed simulation outputs with impulse’s visualization, analysis, and processing tools.

With the FST Reader, you can:
- Efficiently load large, block-compressed FST files with fast random access
- Select and filter signals of interest using include/exclude expressions
- Leverage impulse’s hierarchical browsing and advanced visualization
- Configure time-windowed imports (start/end) to focus on specific ranges
- Benefit from robust progress reporting, error diagnostics, and property integration

The FST Reader integrates into the impulse ecosystem and is designed for high-performance, low-memory parsing of large datasets, supporting multiple compression schemes and advanced per-signal value-change encoding.

## Supporting

This serializer supports:
- PROPERTIES: Provides options to customize serialisation behavior, filtering, and output attributes for serializers.
- CONFIGURATION: The serializer supports configuration management, allowing users to add and select configurations to override default name patterns and properties. 

## Properties

**Signal Selection Properties**
- **Include**: Regular expression pattern to include specific signals during import. Only signals matching this pattern will be imported.
- **Exclude**: Regular expression pattern to exclude specific signals during import. Signals matching this pattern will be filtered out and not imported.

**Time Range and Transformation Properties**
- **Start**: Start time position for importing samples. Only value changes at or after this time will be imported (specified in domain units like ns, us, ms).
- **End**: End time position for importing samples. Only value changes before or at this time will be imported (specified in domain units like ns, us, ms).
- **Delay**: Time offset to shift all timestamps during import. Positive values delay the waveform, negative values advance it (specified in domain units). Applied before dilation.
- **Dilate**: Time scaling factor to stretch or compress the temporal dimension of the waveform. Values > 1.0 slow down time, values < 1.0 speed up time. Applied after delay transformation using formula: (time + delay) * dilate.

**Structural Organization Properties**
- **Resolve Hierarchy**: Organize signals into nested scopes by splitting their names using a regular expression. The regex defines how names are divided into hierarchical parts, with the last part becoming the signal name (e.g., `base.draft.xy` split by `\\.` creates `base/draft/xy`). Useful for records without explicit scopes but structured names. Use cautiously if scopes already exist to avoid redundancy or conflicts.
- **Resolve Vectors**: Enable automatic grouping and resolution of multi-bit vector signals based on bit indices and signal naming conventions.
- **Keep empty scopes**: Preserve empty hierarchical scopes in the signal tree structure even when they contain no actual signals or variables.

**Logging and Diagnostics Properties**
The parser integrates with impulse's console logging system, providing configurable verbosity levels for diagnostic output during the import process. Console properties control the level of detail in parsing progress reports, timing statistics, and error information.

## Format
For a detailed description of the FST file format, refer to [fst-format.md](fst-format.md).

## Known Limitations

- Still in experimental status
- Cant resolve vectors from single bits
- Not performance improved
- Advanced or writer-specific metadata blocks may be recognized but not fully interpreted by the reader; such content is safely ignored.
- Blackout/dump-control ranges (if present) are parsed for timing context but don’t alter imported samples.
- Origiginal FST_BL_VCDATA blocks are not handled, just DYN_ALIAS and DYN_ALIAS2

## Sources and Customization

The reader implementations support conversion to a Custom-based block. This feature allows users to create a new Custom functional block initialized with the same implementation as the singleton. Users can then fix issues or enhance the functionality as needed, providing a path for customization and extension. You may modify, fix, and extend it to fit specific workflows, provided all changes comply with your end user license (EULA). 

To do so, open the reader's preferences and select "Create custom block". A new custom block is created with the full implementation. To use it, disable the original reader or increase the custom block's priority by moving it higher in the list. To edit the custom block, open its preferences and select "Open Editor". Changes take effect immediately upon reload. This enables advanced users to handle proprietary variants, implement custom filtering logic, add specialized preprocessing, integrate with external tools, or fix compatibility issues with specific simulators. 

## Implementation Details

The FST Reader implements a block-oriented parser that transforms FST’s compact binary structure into impulse records and signal writers. It is designed around efficient handle mapping, incremental decompression, and per-signal value change decoding.

### Block-Oriented Parsing Architecture

FST files are composed of typed blocks, each starting with a block type and size. Core types include:
- HEADER (FST_BL_HDR = 0): File-level metadata (time bounds, timescale, counts, endian check, version, file type, timezero)
- VALUE_CHANGE (FST_BL_VCDATA = 1) and DYN_ALIAS variants (5, 8): Time-segmented value changes with per-signal compression
- BLACKOUT (FST_BL_BLACKOUT = 2): Dump on/off periods
- GEOMETRY (FST_BL_GEOM = 3): Handle mapping, bit widths, and viewer geometry
- HIERARCHY (FST_BL_HIER = 4, HIER_LZ4 = 6, HIER_LZ4DUO = 7): Compressed hierarchy tree and signal definitions
- ZWRAPPER (FST_BL_ZWRAPPER = 254) / SKIP (FST_BL_SKIP = 255): Wrapper/placeholder

The reader validates and extracts header fields, then iterates blocks to build hierarchy and decode value changes.

### Incremental Block and Buffer Processing

Parsing proceeds block-by-block. Within blocks, compressed sections are decoded incrementally to bound memory:
- Compression support (depending on block): Zlib/deflate, Gzip, LZ4, dual-stage LZ4DUO, and FastLZ
- Decompression is applied only to the needed section(s), avoiding full-file loads
- Geometry and hierarchy blocks are decompressed to reconstruct handle widths, names, and scope structure

### Signal Management and Handle Resolution

FST uses integer “handles” to reference signals. The reader:
- Builds arrays indexed by handle for fast O(1) access to writers
- Uses geometry to learn bit widths (non-zero = vector width, zero = 64-bit real)
- Creates appropriate impulse writers (logic vs. float) and attaches metadata (name/type/direction) from hierarchy

### Hierarchy and Geometry Processing

Hierarchy blocks define the complete scope tree and variables, using tags compatible with VCD-style scopes and variable types (module, task, function, wire, reg, real, etc.). The reader:
- Parses scope open/close entries to maintain the current path
- Creates WaveformVariable instances per handle with resolved hierarchical names
- Applies attributes (when present) and preserves scope structure (optionally keeping empty scopes)

Geometry blocks provide viewer and structural hints:
- Confirm bit widths and handle ranges
- Supply mapping required to decode frames and value-change chains

### Value Change Processing and Dynamic Aliasing

Value changes are grouped into time-bounded blocks and use compact encodings:
- Initial frame section: compressed snapshot of all signal values at block start
- VC section: per-signal compressed chunks; each handle’s data is at an offset listed in the chain table
- Chain section: offset/length table (with alias encoding for shared waveforms)
- Time section: delta-encoded times, typically zlib-compressed, expanded to absolute timestamps per block

Dynamic alias formats (DYN_ALIAS, DYN_ALIAS2) allow signals with identical waveforms to reference shared data, reducing size. The reader resolves alias entries so aliased handles inherit the referenced value changes without duplication.

### Time Management

The header provides the global timescale and optional timezero offset. Within each VC block, time deltas are decoded and accumulated into absolute timestamps. The Start/End properties allow filtering by time range, skipping out-of-window changes for faster imports.

### Error Handling and Robustness

The parser includes detailed error contexts (block kind/offset, section boundaries) to help diagnose malformed files. It validates consistency across header/geometry/hierarchy (e.g., handle bounds and widths). Resources are cleaned up on cancellation or failures.

