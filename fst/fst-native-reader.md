<!---
title: "FST (Fast Signal Trace) Native Reader"
author: "Thomas Haber"
keywords: [FST, Fast Signal Trace, impulse, EDA, waveform, simulation, signal analysis, digital, parser, extension, compressed, block-compressed, native, gtkwave, fstapi]
description: "The FST Native Reader extension for impulse enables high-performance import and analysis of digital simulation waveforms in the FST format using gtkwave's fstapi library. Supports lazy loading, hierarchical browsing, and seamless integration with impulse's visualization tools via the flux format. Experimental status: subject to change."
category: "impulse-extension"
tags:
  - reference
  - serializer
docID: xxx
--->
# FST (Fast Signal Trace) Native Reader

> ⚠️ Important: Beta
> The FST Native Reader is currently in an beta state. Features, performance, and format coverage may change, and breaking changes can occur. Use for evaluation and testing only; not yet recommended for production workflows.

The FST Reader lets you import and analyze digital simulation waveforms stored in the FST (Fast Signal Trace) binary format inside the impulse framework. It bridges compact, block-compressed simulation outputs with impulse’s visualization, analysis, and processing tools.

With the FST Reader, you can:
- Efficiently load large, block-compressed FST files with fast random access
- Select and filter signals of interest using include/exclude expressions
- Leverage impulse’s hierarchical browsing and advanced visualization
- Configure time-windowed imports (start/end) to focus on specific ranges
- Benefit from robust progress reporting, error diagnostics, and property integration

The FST Native Reader is a native functional block that integrates high-performance C/C++ code for importing and analyzing digital simulation waveforms stored in the FST (Fast Signal Trace) binary format. Built on gtkwave's proven fstapi library, it provides efficient access to compact, block-compressed simulation outputs within the impulse framework.

## Native Implementation Architecture

This reader is implemented as a **native functional block**, integrating C-based implementations into impulse. The core parsing and data extraction logic resides in native code (main.c), which:
- Uses gtkwave's fstapi library for FST file parsing
- Converts FST data to the flux format for efficient data exchange
- Communicates with impulse via the internal flux reader
- Provides both immediate loading and lazy loading (control) modes

## Supporting

This serializer supports:
- PROPERTIES: Provides options to customize serialisation behavior, filtering, and output attributes for serializers.
- CONFIGURATION: The serializer supports configuration management, allowing users to add and select configurations to override default name patterns and properties. 

## Properties

**Signal Selection Properties**
- **Include**: Regular expression pattern to include specific signals during import. Only signals matching this pattern will be imported into the waveform viewer.
- **Exclude**: Regular expression pattern to exclude specific signals during import. Signals matching this pattern will be filtered out and not imported.

**Time Range and Transformation Properties**
- **Start**: Start time position for importing samples. Only value changes at or after this time will be imported (specified in domain units like ns, us, ms).
- **End**: End time position for importing samples. Only value changes before or at this time will be imported (specified in domain units like ns, us, ms).
- **Delay**: Time offset to shift all timestamps during import. Positive values delay the waveform, negative values advance it (specified in domain units). Applied before dilation.
- **Dilate**: Time scaling factor to stretch or compress the temporal dimension of the waveform. Values > 1.0 slow down time, values < 1.0 speed up time. Applied after delay transformation using formula: (time + delay) * dilate.

**Structural Organization Properties**
- **Resolve Hierarchy**: Organize signals into nested scopes by splitting their names using a regular expression. The regex defines how names are divided into hierarchical parts, with the last part becoming the signal name (e.g., `base.draft.xy` split by `\\.` creates `base/draft/xy`). Useful for records without explicit scopes but structured names. Use cautiously if scopes already exist to avoid redundancy or conflicts.
- **Keep empty scopes**: Preserve empty hierarchical scopes in the signal tree structure even when they contain no actual signals or variables.

**Logging and Diagnostics Properties**
The parser integrates with impulse's console logging system, providing configurable verbosity levels for diagnostic output during the import process. Console properties control the level of detail in parsing progress reports, timing statistics, and error information.

**Native Build**

The native block UI provides properties and commands for managing the C-based implementation:

**Build Properties:**
- **Add. Flags**: Additional compiler flags to pass during the build process (e.g., `-DRT` for runtime definitions, optimization flags like `-O2`, architecture-specific options).
- **Add. Libs**: Additional libraries to link during the build process (e.g., `-lrt` for real-time library, `-lm` for math library, `-lpthread` for threading support).
- **Make Command**: Custom make command to use for building the native code (default: `make`). Can be used to specify alternative build systems or make variants.

**Build Commands:**
- **Export and Build**: Exports the native source code (main.c) and dependencies (fstapi, flx), then executes the build process using the configured make command.
- **Clean and Build**: Performs a clean build by removing previous build artifacts before rebuilding from scratch.
- **Open in Terminal**: Opens a terminal window in the native source directory for manual build operations and debugging.
- **Show Main**: Opens the main.c source file in an editor for inspection or modification.
- **Show Makefile**: Opens the Makefile in an editor to review or customize the build process.

**Runtime Options:**

The compiled native reader binary (fst2flx) supports the following command-line options:
- **-h, --help**: Display usage information and exit
- **-l, --lazy**: Enable lazy loading (control mode) for on-demand signal retrieval
- **-c, --compress N**: Set flux compression level (0=none, 1=LZ4, 2=FLZ+LZ4)


## Format
For a detailed description of the FST file format, refer to [fst-format.md](fst-format.md).

## Known Limitations

- Still in experimental status

## Implementation Details

The FST Native Reader is built around a two-stage architecture:

### Stage 1: Native FST Parser (C)
The native implementation (main.c) uses gtkwave's fstapi to:
- Open and parse FST files with efficient block-oriented decompression
- Extract hierarchical scope structure (modules, tasks, functions, etc.)
- Map FST variable types to flux data types:
  - Logic signals (reg, wire, logic, etc.) → FLX_DATA_TYPE_LOGIC
  - Real/floating-point signals → FLX_DATA_TYPE_FLOAT  
  - String signals → FLX_DATA_TYPE_TEXT
- Handle scattered signals (bit slices) with automatic name parsing
- Support lazy loading via control protocol (FLX_CONTROL_DB_REQ_SIGNALS)
- Stream value changes with configurable flux compression

### Stage 2: Flux Import (Java)
The converted flux stream is imported into impulse using the internal flux reader, which:
- Deserializes flux format data into impulse records
- Creates signal writers and manages hierarchical structure
- Handles control messages for lazy loading mode
- Provides integration with impulse's visualization and analysis tools

### Time Scale Handling
The reader automatically converts FST timescale (power-of-10 exponents from -18 to 2) to appropriate flux domain bases:
- Supports from attoseconds (as) to 100 seconds (s100)
- Applies timezero offset from FST metadata
- Maps FST time range to flux domain start/end

### Supported FST Variable Types
- Logic types: event, reg, wire, logic, bit, int, etc.
- Real types: real, real_parameter, shortreal
- String type: gen_string
- Full VHDL and SystemVerilog type mappings per FST specification

## Source Code Structure

### Main Entry Point (main.c)

```c
int main(int argc, char **argv) {
    // Parse arguments, open FST file
    fstObject = fstReaderOpen(fstFilename);
    
    // Detect geometry
    maxSignals = fstReaderGetMaxHandle(fstObject) + 1;
    
    // Initialize buffers and trace
    trace = flxCreateTrace(...);
    
    // Process hierarchy and data
    traceAllItems();
    openTrace();
    
    if (lazy) {
        // Enter control loop for on-demand signal loading
        flxParseControlInput(stdin, MAX_ENTRY_SIZE, handleCommands);
    } else {
        traceAllChanges();
        closeTrace();
    }
}
```

### Hierarchy Traversal

```c
static void traceAllItems() {
    maxScopes = 1;
    currentScope = 0;
    struct fstHier *h;
    fstReaderIterateHierRewind(fstObject);
    
    while ((h = fstReaderIterateHier(fstObject))) {
        switch (h->htyp) {
        case FST_HT_SCOPE:
            traceScope(&h->u.scope);
            break;
        case FST_HT_UPSCOPE:
            currentScope = trace->items[currentScope - 1].parentId;
            break;
        case FST_HT_VAR:
            traceVar(&h->u.var);
            break;
        }
    }
}

static void traceVar(struct fstHierVar* var) {
    // Map FST types to flux types
    flxbyte type = FLX_DATA_TYPE_LOGIC;
    if (var->typ == FST_VT_VCD_REAL)
        type = FLX_DATA_TYPE_FLOAT;
    else if (var->typ == FST_VT_GEN_STRING)
        type = FLX_DATA_TYPE_TEXT;
    
    // Parse bit-slice notation [msb:lsb]
    // Extract from/to if present in signal name
    
    // Add signal to trace
    flxAddSignal(trace, var->handle, currentScope, varname, ...);
}
```

### Value Change Processing

```c
void traceChange(void *user_callback_data_pointer, uint64_t time, 
                 fstHandle facidx, const unsigned char *value) {
    
    flxbyte type = trace->items[facidx - 1].signalType;
    time += zero;  // Apply time offset
    
    switch (type) {
    case FLX_DATA_TYPE_LOGIC:
        // Detect X/U conflict states
        int conflict = (strchr(value, 'x') || strchr(value, 'X'));
        flxWriteLogicTextAt(trace, facidx, conflict, time, 0, 
                           FLX_STATE_0_BITS, value, len, scale);
        break;
        
    case FLX_DATA_TYPE_FLOAT:
        double v = atof(value);
        flxWriteFloatAt(trace, facidx, 0, time, 0, &v, 8);
        break;
        
    case FLX_DATA_TYPE_TEXT:
        flxWriteTextAt(trace, facidx, 0, time, 0, value, len);
        break;
    }
}

void traceAllChanges() {
    fstReaderSetFacProcessMaskAll(fstObject);
    fstReaderIterBlocks2(fstObject, traceChange, traceChangeVar, 0, 0);
}
```

### Lazy Loading

```c
flxresult handleReqSignals(...) {
    // Parse requested signal IDs from control message
    // ...extract itemIds from binary parameter...
    
    // Clear masks and enable only requested signals
    fstReaderClrFacProcessMaskAll(fstObject);
    for (pos = 0; pos < count; pos++)
        if (flxIsSignal(trace, itemIds[pos]))
            fstReaderSetFacProcessMask(fstObject, itemIds[pos]);
    
    // Process value changes for selected signals only
    fstReaderIterBlocks2(fstObject, traceChange, traceChangeVar, 0, 0);
    
    // Send response
    flxWriteControlResponse(trace, controlId, messageId, 0, 0);
    flxFlush(trace);
}
```

### Build Requirements

**Required Libraries:**
- gtkwave's fstapi library
- flux library (included with impulse)
- Posix make and C compiler