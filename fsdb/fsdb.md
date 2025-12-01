# FSDB (Fast Signal Database) File Format

## Overview

FSDB (Fast Signal Database) is a proprietary binary waveform file format developed and owned by Synopsys, Inc. It is the native format used by Synopsys VCS (Verilog Compiler Simulator) and other Synopsys simulation tools for storing digital and analog signal trace data.

**Important**: FSDB is a closed, proprietary format. The internal file structure and encoding methods are not publicly documented. Access to FSDB files requires:
- Licensed Synopsys FSDB reader libraries
- Tools that have licensed the FSDB technology from Synopsys
- Conversion to open formats like VCD

## Known Capabilities

### Supported Signal Types

Based on the FSDB API documentation, the format supports:

- **Logic Signals**: Standard Verilog 4-state logic (0, 1, X, Z)
- **Integer Signals**: Signed and unsigned integer values
- **Real Signals**: IEEE 754 double-precision floating-point
- **String Signals**: Text string values
- **Register Types**: reg, wire, tri, and other Verilog net types
- **Bit Vectors**: Multi-bit buses and arrays

### Design Hierarchy Support

- Full preservation of Verilog/SystemVerilog module hierarchy
- Support for scopes: modules, tasks, functions, blocks
- Hierarchical signal naming with full path qualification
- Attribute and parameter preservation

### Performance Characteristics (Observed)

- **Compression**: Typically 10:1 to 100:1 vs equivalent VCD files
- **Random Access**: Fast seeking to arbitrary time points
- **Scalability**: Handles millions of signals efficiently
- **Streaming**: Supports incremental writing during simulation
- **Memory Efficiency**: Low memory footprint during reading

## Accessing FSDB Files

### Official Synopsys Tools

**FSDB Reader Library**:
- Provided with Synopsys VCS and other simulation products
- Available for Linux, Windows, and Solaris platforms
- C/C++ API with optional Python bindings
- Requires active Synopsys license

**Verdi/DVE Waveform Viewers**:
- Native FSDB support through Synopsys toolchain
- Interactive browsing, searching, and analysis
- Signal extraction and filtering capabilities

### API Access Pattern

```c
// Conceptual usage (actual API may differ)
// Requires Synopsys FSDB reader library

// Open file
void* fsdb_handle = fsdbReader_open("simulation.fsdb");

// Query metadata  
uint64_t start_time = fsdbReader_getStartTime(fsdb_handle);
uint64_t end_time = fsdbReader_getEndTime(fsdb_handle);

// Access hierarchy
void* hier_handle = fsdbReader_getHierarchy(fsdb_handle);

// Iterate signals and read value changes
// ...

fsdbReader_close(fsdb_handle);
```

**Note**: Actual API details require Synopsys documentation and licensing.

## Practical Usage Example

Basic FSDB file processing based on the ffrAPI:

```c
#include "ffrAPI.h"

int main(int argc, char *argv[]) {
    // Validate and open FSDB
    if (!ffrObject::ffrIsFSDB(argv[1])) {
        fprintf(stderr, "Not a valid FSDB file\n");
        return 1;
    }
    
    ffrObject *fsdbObj = ffrObject::ffrOpen3(argv[1]);
    if (!fsdbObj) return 1;
    
    // Get time bounds
    fsdbTag64 startTime, endTime;
    fsdbObj->ffrGetMinFsdbTag64(&startTime);
    fsdbObj->ffrGetMaxFsdbTag64(&endTime);
    uint64_t start = ((uint64_t)startTime.H << 32) | startTime.L;
    uint64_t end = ((uint64_t)endTime.H << 32) | endTime.L;
    
    // Get timescale
    uint_T digit;
    char *unit;
    fsdbObj->ffrExtractScaleUnit(fsdbObj->ffrGetScaleUnit(), digit, unit);
    printf("Timescale: %u%s, Range: %llu to %llu\n", digit, unit, start, end);
    
    // Read hierarchy with callback
    fsdbObj->ffrSetTreeCBFunc(hierarchyCallback, NULL);
    fsdbObj->ffrReadScopeVarTree();
    
    // Load signals and create traversal handle
    uint32_t maxSignals = fsdbObj->ffrGetMaxVarIdcode();
    fsdbVarIdcode signalIds[maxSignals];
    for (uint32_t i = 1; i <= maxSignals; i++) {
        fsdbObj->ffrAddToSignalList(i);
        signalIds[i-1] = i;
    }
    fsdbObj->ffrLoadSignals();
    
    // Traverse value changes
    ffrTimeBasedVCTrvsHdl vcTrvsHdl = 
        fsdbObj->ffrCreateTimeBasedVCTrvsHdl(maxSignals, signalIds);
    
    byte_T *valueData;
    fsdbVarIdcode currentId;
    while (vcTrvsHdl && vcTrvsHdl->ffrGotoNextVC() == FSDB_RC_SUCCESS) {
        vcTrvsHdl->ffrGetVC(&valueData);
        vcTrvsHdl->ffrGetXTag(&startTime);
        vcTrvsHdl->ffrGetVarIdcode(&currentId);
        
        uint64_t timestamp = ((uint64_t)startTime.H << 32) | startTime.L;
        // Process valueData for currentId at timestamp
    }
    
    fsdbObj->ffrUnloadSignals();
    fsdbObj->ffrClose();
    return 0;
}
```

## Format Conversion

FSDB files can be converted to other formats using Synopsys tools or third-party utilities. Common conversion targets include:

- **VCD (Value Change Dump)**: Standard ASCII format for waveform data. Supported by many simulators and waveform viewers.
- **SAIF (Switching Activity Interchange Format)**: Used for power analysis tools to estimate dynamic power consumption.
- **WLF (Waveform Library Format)**: Used by Mentor Graphics tools.

### Using Synopsys vcd2fsdb Tool

```bash
vcd2fsdb -f input.vcd -o output.fsdb
```

### Using fsdbDump

```bash
fsdbDump -f input.fsdb -o output.vcd
```

### Using Verdi for Conversion

1. Open the VCD file in Verdi.
2. Use the `File > Export > FSDB` menu option.
3. Choose the output FSDB file name and options.
4. Click `OK` to convert.

