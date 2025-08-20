# VCD (Value Change Dump) Reader

The VCD Reader enables users to import and analyze digital simulation waveforms in the widely adopted VCD (Value Change Dump) format within the impulse framework. Designed for engineers and verification specialists, it provides a seamless bridge between simulation outputs and impulse's powerful visualization, analysis, and processing tools.

With the VCD Reader, users can:
- Efficiently load large VCD files from digital circuit simulations
- Select and filter signals of interest for focused analysis
- Leverage impulse's hierarchical signal browsing and advanced visualization features
- Configure import settings to match project requirements, including time range selection and signal grouping
- Benefit from robust error handling, progress reporting, and integration with impulse's property and logging systems

The VCD Reader is fully integrated into the impulse ecosystem, supporting flexible configuration and high-performance data handling for both small and large simulation datasets.

## Properties

The parser supports extensive configuration through impulse's property system, allowing fine-grained control over the import process. Signal filtering can be applied using regular expressions for both inclusion and exclusion patterns. This allows users to import only specific signals of interest from large VCD files containing thousands of signals.

The VCD Reader exposes a comprehensive set of configurable properties that control various aspects of the parsing and import process:

**Signal Selection Properties**
- **Include**: Regular expression pattern to include specific signals during import. Only signals matching this pattern will be imported into the waveform viewer.
- **Exclude**: Regular expression pattern to exclude specific signals during import. Signals matching this pattern will be filtered out and not imported.

**Time Range and Transformation Properties**
- **Start**: Start time position for importing samples. Only value changes at or after this time will be imported (specified in domain units like ns, us, ms).
- **End**: End time position for importing samples. Only value changes before or at this time will be imported (specified in domain units like ns, us, ms).
- **Delay**: Time offset to shift all timestamps during import. Positive values delay the waveform, negative values advance it (specified in domain units). Applied before dilation.
- **Dilate**: Time scaling factor to stretch or compress the temporal dimension of the waveform. Values > 1.0 slow down time, values < 1.0 speed up time. Applied after delay transformation using formula: (time + delay) * dilate.

**Structural Organization Properties**
- **Resolve Hierarchy**: Enable hierarchical signal organization by creating nested scope structures based on signal names. Enter the name split regex to enable.
- **Resolve Vectors**: Enable automatic grouping and resolution of multi-bit vector signals based on bit indices and signal naming conventions.
- **Keep empty scopes**: Preserve empty hierarchical scopes in the signal tree structure even when they contain no actual signals or variables.

**Logging and Diagnostics Properties**
The parser integrates with impulse's console logging system, providing configurable verbosity levels for diagnostic output during the import process. Console properties control the level of detail in parsing progress reports, timing statistics, and error information.

## Known Limitation

- The VCD Reader does not support the `$dumpall`, `$dumpon`, and `$dumpoff` commands. These commands are recognized but ignored during parsing; any value changes triggered by these commands will not be processed or reflected in the imported data.

## Implementation Details

The VCD Reader provides a comprehensive parser that imports VCD files into the impulse framework, transforming textual waveform data into impulse's native record and signal structures. The implementation is designed around a high-performance, token-based parsing engine that can handle large VCD files efficiently while maintaining reasonable memory usage.

### Token-Based Parsing Architecture

The core of the parser is built around a fast token classification system using a 256-element lookup table. Each byte in the input stream is immediately classified into one of several token types including commands (starting with '$'), time markers (starting with '#'), vector changes (starting with 'b' or 'B'), real changes (starting with 'r' or 'R'), string changes (starting with 's' or 'S'), and various logic state changes. This direct byte-to-token mapping eliminates the need for complex character-by-character analysis and significantly improves parsing performance.

The token system handles multiple logic levels natively. Two-state logic uses simple '0' and '1' characters, four-state logic adds 'X', 'x', 'Z', and 'z' for unknown and high-impedance states, while sixteen-state logic includes additional strength levels represented by 'H', 'h', 'L', 'l', 'U', 'u', 'W', 'w', and '-' characters. Each character is pre-mapped to its corresponding state value in the lookup table.

### Incremental Buffer Processing

The parser processes input in configurable chunks rather than loading entire files into memory. The default buffer size is 64KB, which provides good performance while keeping memory usage bounded. The implementation carefully handles partial tokens that span buffer boundaries by maintaining state across buffer reads and reassembling tokens as needed.

Automatic decompression is supported through the framework's stream handling, allowing compressed VCD files to be processed transparently. This is particularly useful for large simulation results that are often stored in compressed formats to save disk space.

### Signal Management and Identifier Resolution

VCD files use compact identifiers for signals, typically single characters or short strings from the printable ASCII range. The parser maps these VCD identifiers to impulse sample writers using two strategies depending on the identifier distribution. For dense identifier spaces where identifiers can be efficiently mapped to array indices, a fast array-based lookup is constructed. This provides O(1) access time for value changes, which is critical during the intensive value change processing phase.

When identifiers are sparse or don't map well to array indices, the implementation falls back to hash map lookup. The parser analyzes the identifier space during initialization and automatically selects the most appropriate lookup strategy. This hybrid approach ensures optimal performance across different VCD file characteristics.

### VCD Command Processing

The parser recognizes all standard VCD commands including variable declarations, scope definitions, timescale specifications, and dump control commands. Variable declarations are parsed using regular expressions to extract the signal type, bit width, VCD identifier, and hierarchical name. The parser handles bit range specifications in signal names, properly extracting high and low indices for vector signals.

Scope commands build a hierarchical structure that matches the original HDL design hierarchy. The parser maintains a current scope context and creates nested record scopes as it encounters scope and upscope commands. This hierarchy is later used for signal organization and can optionally be presented to users as a hierarchical signal browser.

Timescale commands are processed to establish the time base for the entire file. The parser supports all standard VCD time units from femtoseconds to seconds, including decimal factors like 10ps and 100ns. The parsed timescale is converted to impulse's internal time base representation and used for all subsequent timestamp processing.

### Value Change Processing

Value changes form the bulk of most VCD files and require efficient processing to maintain reasonable import times. The parser handles several types of value changes including scalar logic changes, vector logic changes, real number changes, and string changes.

Scalar logic changes are the most common and are processed with minimal overhead. The parser extracts the new state value and VCD identifier, looks up the corresponding sample writer, and records the change at the current timestamp.

Vector changes require more complex processing as they can contain arbitrarily long bit patterns. The parser reads the vector pattern into an internal buffer, analyzes it to determine the appropriate logic level, and applies compression techniques to minimize storage requirements. Vectors with repeating patterns are compressed by identifying a preceding state and storing only the differing bits.

Real number changes are parsed using standard floating-point parsing and stored using impulse's floating-point sample writers. String changes are handled similarly, with the text content extracted and stored using text sample writers.

### Time Management and Transformation

The parser processes time markers to maintain the current simulation timestamp. Time values from the VCD file can be transformed through several mechanisms including scale factors, offset adjustments, and delay applications. These transformations are applied during timestamp processing to align simulation data with analysis requirements.

Time range filtering is supported through start and end time properties. The parser can skip value changes outside the specified time window, which is useful when analyzing specific portions of long simulations. The record is only opened when the first timestamp within the specified range is encountered, and closed when the end time is exceeded.

### Error Handling and Robustness

The parser includes comprehensive error handling with detailed error messages that include file position context. When parse errors occur, the error message indicates the specific location in the file and provides text context around the problematic area. This helps users identify and correct issues with malformed VCD files.

The implementation validates VCD semantic constraints such as ensuring real and string signals don't have vector specifications, and verifying that shared identifiers maintain consistent bit widths across multiple declarations.

Memory management is carefully controlled to prevent resource exhaustion when processing large files. The parser cleans up temporary structures as they are no longer needed and provides proper resource cleanup on cancellation or error conditions.

### Integration Points

The VCD Reader integrates seamlessly with impulse's progress reporting system, providing cancellation support and progress feedback during long import operations. Console logging provides diagnostic information about the parsing process, including timing statistics and signal counts.

The parsed data is stored using impulse's sample writers, which provide efficient storage and retrieval for different signal types. The resulting record structure preserves all signal metadata including names, hierarchies, and type information, enabling full integration with impulse's analysis and visualization capabilities.
