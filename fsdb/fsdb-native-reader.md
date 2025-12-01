<!---
title: "FSDB (Fast Signal Database) Native Reader"
author: "Thomas Haber"
keywords: [FSDB, Fast Signal Database, impulse, EDA, waveform, simulation, signal analysis, digital, parser, extension, compressed, native, Synopsys, ffrAPI]
description: "The FSDB Native Reader extension for impulse enables high-performance import and analysis of digital simulation waveforms in Synopsys FSDB format using the ffrAPI library. Supports lazy loading, hierarchical browsing, and seamless integration with impulse's visualization tools via the flux format. Experimental status: subject to change."
category: "impulse-extension"
tags:
  - reference
  - serializer
docID: xxx
--->
# FSDB (Fast Signal Database) Native Reader

> ⚠️ Important: Beta
> The FSDB Native Reader is currently in a beta state. Features, performance, and format coverage may change, and breaking changes can occur. Use for evaluation and testing only; not yet recommended for production workflows.

The FSDB Reader lets you import and analyze digital simulation waveforms stored in Synopsys FSDB (Fast Signal Database) format inside the impulse framework. It bridges highly compressed, proprietary simulation outputs with impulse's visualization, analysis, and processing tools.

With the FSDB Reader, you can:
- Efficiently load large, compressed FSDB files with fast random access
- Select and filter signals of interest using include/exclude expressions
- Leverage impulse's hierarchical browsing and advanced visualization
- Configure time-windowed imports (start/end) to focus on specific ranges
- Benefit from robust progress reporting, error diagnostics, and property integration

The FSDB Native Reader is a native functional block that integrates high-performance C/C++ code for importing and analyzing digital simulation waveforms stored in Synopsys proprietary FSDB format. Built on Synopsys ffrAPI library (Fast FSDB Reader), it provides efficient access to highly compressed simulation outputs within the impulse framework.

## Native Implementation Architecture

This reader is implemented as a **native functional block**, integrating C++-based implementations into impulse. The core parsing and data extraction logic resides in native code (main.cc), which:
- Uses Synopsys ffrAPI library for FSDB file parsing
- Converts FSDB data to the flux format for efficient data exchange
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
- **Start**: Start time position for importing samples. Only value changes at or after this time will be imported (specified in domain units like ps, ns, us, ms).
- **End**: End time position for importing samples. Only value changes before or at this time will be imported (specified in domain units like ps, ns, us, ms).
- **Delay**: Time offset to shift all timestamps during import. Positive values delay the waveform, negative values advance it (specified in domain units). Applied before dilation.
- **Dilate**: Time scaling factor to stretch or compress the temporal dimension of the waveform. Values > 1.0 slow down time, values < 1.0 speed up time. Applied after delay transformation using formula: (time + delay) * dilate.

**Structural Organization Properties**
- **Resolve Hierarchy**: Organize signals into nested scopes by splitting their names using a regular expression. The regex defines how names are divided into hierarchical parts, with the last part becoming the signal name (e.g., `base.draft.xy` split by `\\.` creates `base/draft/xy`). Useful for records without explicit scopes but structured names. Use cautiously if scopes already exist to avoid redundancy or conflicts.
- **Keep empty scopes**: Preserve empty hierarchical scopes in the signal tree structure even when they contain no actual signals or variables.

**Logging and Diagnostics Properties**
The parser integrates with impulse's console logging system, providing configurable verbosity levels for diagnostic output during the import process. Console properties control the level of detail in parsing progress reports, timing statistics, and error information.

**Native Build**

The native block UI provides properties and commands for managing the C++-based implementation:

**Build Properties:**
- **Add. Flags**: Additional compiler flags to pass during the build process (e.g., `-DRT` for runtime definitions, optimization flags like `-O2`, architecture-specific options).
- **Add. Libs**: Additional libraries to link during the build process (e.g., `-lrt` for real-time library, `-lm` for math library, `-lpthread` for threading support).
- **Make Command**: Custom make command to use for building the native code (default: `make`). Can be used to specify alternative build systems or make variants.
- **Lib**: Path to the shared libraries directory containing the FSDB native libraries for the current operating system (e.g. libnffr.so on Linux).
- **Inc**: Path to the include directory containing the FSDB header files (e.g. ffrAPI.h).

**Build Commands:**
- **Export and Build**: Exports the native source code (main.cc) and dependencies (flx), then executes the build process using the configured make command.
- **Clean and Build**: Performs a clean build by removing previous build artifacts before rebuilding from scratch.
- **Open in Terminal**: Opens a terminal window in the native source directory for manual build operations and debugging.
- **Show Main**: Opens the main.cc source file in an editor for inspection or modification.
- **Show Makefile**: Opens the Makefile in an editor to review or customize the build process.

**Runtime Options:**

The compiled native reader binary (fsdb2flx) supports the following command-line options:
- **-h, --help**: Display usage information and exit
- **-l, --lazy**: Enable lazy loading (control mode) for on-demand signal retrieval
- **-c, --compress N**: Set flux compression level (0=none, 1=LZ4, 2=FLZ+LZ4)

## Format
For a detailed description of the FSDB file format, refer to [fsdb.md](fsdb.md).

## Known Limitations

- Requires valid Synopsys license and FSDB reader library
- Still in experimental status
- Proprietary format dependencies limit portability

## Implementation Details

The FSDB Native Reader is built around a two-stage architecture:

### Stage 1: Native FSDB Parser (C++)
The native implementation (main.cc) uses Synopsys ffrAPI to:
- Open and validate FSDB files with format detection
- Extract hierarchical scope structure with callback-based traversal:
  - Modules, tasks, functions, begin/fork blocks (Verilog)
  - Architecture, procedure, function, record, process blocks (VHDL)
  - Structures and arrays (SystemVerilog)
- Map FSDB variable types to flux data types:
  - Logic signals (wire, reg, tri, etc.) → FLX_DATA_TYPE_LOGIC with 9-state support
  - Real/floating-point signals → FLX_DATA_TYPE_FLOAT
  - Integer signals → FLX_DATA_TYPE_INTEGER
  - String signals → FLX_DATA_TYPE_TEXT
- Handle scattered signals (bit slices) with automatic name parsing and range detection
- Support lazy loading via control protocol (FLX_CONTROL_DB_REQ_SIGNALS)
- Stream value changes using time-based traversal handles
- Configure flux compression for output optimization

### Stage 2: Flux Import (Java)
The converted flux stream is imported into impulse using the internal flux reader, which:
- Deserializes flux format data into impulse records
- Creates signal writers and manages hierarchical structure
- Handles control messages for lazy loading mode
- Provides integration with impulse's visualization and analysis tools

### Time Scale Handling
The reader automatically converts FSDB timescale to appropriate flux domain bases:
- Extracts timescale from FSDB metadata using ffrExtractScaleUnit()
- Supports standard time units: fs, ps, ns, us, ms, s with multipliers
- Applies time zero offset from FSDB file
- Converts FSDB 64-bit timestamps (fsdbTag64) to flux domain values

### Supported FSDB Variable Types

**Verilog Signal Types:**
- Logic types: event, integer, parameter, real, reg, supply0/1, time
- Net types: tri, triand, trior, trireg, tri0/1, wand, wire, wor
- Memory types: vcd_memory, vcd_memory_depth

**VHDL Signal Types:**
- VHDL types: signal, variable, constant, file
- Memory types: vhdl_memory, vhdl_memory_depth

**Data Type Mapping:**
The reader handles FSDB's value change data types (fsdbVCDataType):
- FSDB_VC_DT_BYTE/SHORT/INT/LONG → FLX_DATA_TYPE_INTEGER
- FSDB_VC_DT_FLOAT/DOUBLE → FLX_DATA_TYPE_FLOAT
- FSDB_VC_DT_UNKNOWN → Inferred from variable type

### Logic State Encoding

**Verilog 4-State Logic:**
- FSDB_BT_VCD_0 → FLX_STATE_0_BITS ('0')
- FSDB_BT_VCD_1 → FLX_STATE_1_BITS ('1')
- FSDB_BT_VCD_X → FLX_STATE_X_BITS ('X')
- FSDB_BT_VCD_Z → FLX_STATE_Z_BITS ('Z')

**VHDL 9-State Logic (std_ulogic):**
- FSDB_BT_VHDL_STD_ULOGIC_U → FLX_STATE_U_BITS ('U' - Uninitialized)
- FSDB_BT_VHDL_STD_ULOGIC_X → FLX_STATE_X_BITS ('X' - Unknown)
- FSDB_BT_VHDL_STD_ULOGIC_0 → FLX_STATE_0_BITS ('0' - Strong 0)
- FSDB_BT_VHDL_STD_ULOGIC_1 → FLX_STATE_1_BITS ('1' - Strong 1)
- FSDB_BT_VHDL_STD_ULOGIC_Z → FLX_STATE_Z_BITS ('Z' - High impedance)
- FSDB_BT_VHDL_STD_ULOGIC_W → FLX_STATE_W_BITS ('W' - Weak unknown)
- FSDB_BT_VHDL_STD_ULOGIC_L → FLX_STATE_L_BITS ('L' - Weak 0)
- FSDB_BT_VHDL_STD_ULOGIC_H → FLX_STATE_H_BITS ('H' - Weak 1)
- FSDB_BT_VHDL_STD_ULOGIC_DASH → FLX_STATE_D_BITS ('-' - Don't care)

### Signal Naming and Bit Slicing

The reader automatically detects and handles bit-sliced signals:
- Parses signal names for bit range notation: `signal[7:0]`, `bus[15:8]`
- Extracts bit positions from rightmost bracketed range
- Creates scattered signal definitions for proper bit ordering
- Preserves original signal names after stripping bit ranges
- Validates bit width consistency between name and FSDB metadata

### Hierarchy Traversal

The reader uses callback-based hierarchy traversal:

```cpp
// Callback function processes hierarchy events
static bool_T traceTreeItem(fsdbTreeCBType cbType, 
                            void *clientData, 
                            void *treeCbData) {
    switch (cbType) {
        case FSDB_TREE_CBT_SCOPE:
            // Process scope definition
            traceScope((fsdbTreeCBDataScope*)treeCbData, clientData);
            break;
            
        case FSDB_TREE_CBT_VAR:
            // Process variable/signal definition
            traceVar((fsdbTreeCBDataVar*)treeCbData, clientData);
            break;
            
        case FSDB_TREE_CBT_UPSCOPE:
            // Exit current scope
            currentScope = trace->items[currentScope - 1].parentId;
            break;
            
        case FSDB_TREE_CBT_STRUCT_BEGIN:
            // Process structure definition
            traceStruct((fsdbTreeCBDataStructBegin*)treeCbData, clientData);
            break;
    }
    return true;
}

// Set callback and read hierarchy
fsdbObj->ffrSetTreeCBFunc(traceTreeItem, NULL);
fsdbObj->ffrReadScopeVarTree();
```

### Value Change Processing

The reader uses time-based traversal for efficient value change extraction:

```cpp
// Create traversal handle for selected signals
fsdbVarIdcode signalIds[signalCount];
ffrTimeBasedVCTrvsHdl vcTrvsHdl = 
    fsdbObj->ffrCreateTimeBasedVCTrvsHdl(signalCount, signalIds);

// Process initial value
byte_T *valueData;
fsdbTag64 time;
fsdbVarIdcode currentId;

if (vcTrvsHdl->ffrGetVC(&valueData) == FSDB_RC_SUCCESS) {
    vcTrvsHdl->ffrGetXTag(&time);
    vcTrvsHdl->ffrGetVarIdcode(&currentId);
    
    uint64_t timestamp = ((uint64_t)time.H << 32) | time.L;
    traceValueChange(currentId, vcTrvsHdl, valueData, timestamp);
}

// Iterate through value changes
while (vcTrvsHdl->ffrGotoNextVC() == FSDB_RC_SUCCESS) {
    vcTrvsHdl->ffrGetVC(&valueData);
    vcTrvsHdl->ffrGetXTag(&time);
    vcTrvsHdl->ffrGetVarIdcode(&currentId);
    
    uint64_t timestamp = ((uint64_t)time.H << 32) | time.L;
    traceValueChange(currentId, vcTrvsHdl, valueData, timestamp);
}
```

### Lazy Loading Mode

In lazy loading mode (--lazy flag), the reader:
1. Processes hierarchy and opens trace
2. Sends control scheme with version and item limits
3. Waits for signal requests via FLX_CONTROL_DB_REQ_SIGNALS
4. Loads only requested signals using ffrAddToSignalList()
5. Creates time-based traversal for selected signals only
6. Streams value changes for requested signals
7. Unloads signals and waits for next request

This enables efficient handling of large FSDB files with millions of signals by loading only what's needed.


### Build Requirements

**Required Libraries:**
- Synopsys ffrAPI library 
- Standard C++ libraries
- flux library (included with impulse)
- Posix make and compiler 

**License Requirements:**
- Valid Synopsys VCS or equivalent license
- Access to licensed FSDB reader libraries
- Compliance with Synopsys EULA terms

