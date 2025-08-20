# FST (Fast Signal Trace) File Format Specification

## Overview

The FST (Fast Signal Trace) format is a binary file format designed for efficient storage and retrieval of digital waveform and signal trace data from simulations. FST provides high compression ratios while maintaining fast random access to signals, supporting both digital (logic) and analog (float) data types with extensive metadata capabilities.

FST was developed as a more efficient alternative to VCD (Value Change Dump) and other waveform formats, particularly for large datasets. It employs several compression techniques including block compression, value change encoding, and hierarchical data organization.

## File Structure
[text](fstapi.c) [text](fstapi.h)
An FST file consists of a sequential arrangement of blocks, each identified by a block type marker and containing specific data. The file follows a header-first, data-blocks-middle, hierarchy-last organization pattern.

### Block Type Identification

Each block begins with a single-byte block type identifier that determines how the block should be interpreted:

#### Core Block Types
- `FST_BL_HDR` (0x00): **File header block** - Contains essential file metadata, simulation bounds, signal counts, and format validation information. Must be first block.
- `FST_BL_VCDATA` (0x01): **Value change data block** - Stores compressed simulation waveform data with time-ordered signal transitions. Core data storage.
- `FST_BL_BLACKOUT` (0x02): **Blackout/dump control block** - Defines time periods when signal dumping was disabled for optimization or debugging.
- `FST_BL_GEOM` (0x03): **Geometry data block** - Contains signal handle assignments, bit widths, and waveform viewer display preferences.
- `FST_BL_HIER` (0x04): **Hierarchy definition block** - Defines complete signal hierarchy, names, types, and design structure.
- `FST_BL_SKIP` (0xFF): **Skip block** - Placeholder used during file writing, can be safely ignored by readers.

#### Advanced Value Change Block Types
- `FST_BL_VCDATA_DYN_ALIAS` (0x05): **Dynamic aliasing value change data** - Enhanced compression using signal aliasing for identical waveforms.
- `FST_BL_VCDATA_DYN_ALIAS2` (0x08): **Enhanced dynamic aliasing** - Further improved compression with advanced aliasing algorithms.

#### Compressed Hierarchy Variants
- `FST_BL_HIER_LZ4` (0x06): **LZ4-compressed hierarchy** - Single-stage LZ4 compression for faster decompression than gzip.
- `FST_BL_HIER_LZ4DUO` (0x07): **Dual-stage LZ4 hierarchy** - Two-stage LZ4 compression for maximum compression ratio.

#### Wrapper Block Types
- `FST_BL_ZWRAPPER` (0xFE): **File compression wrapper** - Indicates entire file is gzip compressed (rare usage).

### Block Organization Flow

#### 1. File Header Block (`FST_BL_HDR`)

**Purpose**: The header block contains essential metadata about the entire FST file, including simulation time bounds, signal counts, and file structure information. This block must be read first to understand the file's contents and validate compatibility.

**Position**: Always first block at file offset 0
**Size**: Fixed 330 bytes

**Content Description**:
- **Time Information**: Start and end times define the simulation's temporal scope
- **Signal Metadata**: Counts of scopes and variables help allocate parsing structures
- **Handle Management**: Maximum handle ID sets bounds for signal reference validation
- **Format Validation**: Endian test ensures correct byte order interpretation
- **Simulation Context**: Version strings and file type provide simulation tool information
- **Compression Hints**: Section count indicates how many value change blocks to expect

**Structure**:
```
+0:   Block type (FST_BL_HDR)
+1:   Section length (329 bytes)
+9:   Start time (8 bytes)
+17:  End time (8 bytes) 
+25:  Endian test value (8 bytes double)
+33:  Memory used by writer (8 bytes)
+41:  Number of scopes (8 bytes)
+49:  Number of variables (8 bytes)
+57:  Maximum handle ID (8 bytes)
+65:  Value change section count (8 bytes)
+73:  Timescale (1 byte)
+74:  Simulation version string (128 bytes)
+202: Date string (119 bytes)
+321: File type (1 byte)
+322: Time zero offset (8 bytes)
```

#### 2. Value Change Data Blocks

**Purpose**: Value change blocks contain the actual simulation data - the time-ordered sequence of signal value transitions. These blocks are the core of the FST format, storing compressed waveform data with efficient delta encoding and compression techniques.

**Position**: Sequential blocks following header
**Identification**: Block type determines variant and compression algorithm used

**Content Description**:
- **Time Segmentation**: Each block covers a specific time range, enabling efficient random access
- **Signal State Frames**: Compressed snapshots of all signal values at the block's start time
- **Value Change Chains**: Delta-encoded sequences of signal transitions within the time range
- **Index Structures**: Compressed lookup tables for fast signal access within the block
- **Temporal Data**: Delta-encoded time values for precise transition timing

**Data Organization Benefits**:
- **Fast Random Access**: Blocks can be skipped to jump to specific time ranges
- **Efficient Storage**: Multiple compression layers reduce file size significantly
- **Parallel Processing**: Independent blocks can be processed concurrently
- **Memory Management**: Blocks can be loaded/unloaded as needed during traversal



##### FST Dynamic Alias Block Structure

| Field | Type | Description |
|-------|------|-------------|
| **FST_BL_VCDATA_DYN_ALIAS Block** | byte[section_length] | Complete block with backwards-calculated layout. Per-signal compression: each signal has individual compressed chunk at chain_table[handle] offset from vc_start. Chain table provides offsets and sizes. Processing: seek to vc_start + chain_table[i], read varint (>0=compressed size, 0=raw), decompress per pack_type if needed. |
| &nbsp;&nbsp;**Block Header** | byte[33] | Standard FST block header (fixed 33 bytes: 1+8+8+8+8) |
| &nbsp;&nbsp;&nbsp;&nbsp;block_type | uint8 | Block type (3=DYN_ALIAS, 4=DYN_ALIAS2) |
| &nbsp;&nbsp;&nbsp;&nbsp;section_length | uint64 | Total block size in bytes |
| &nbsp;&nbsp;&nbsp;&nbsp;start_time | uint64 | Starting timestamp for this block |
| &nbsp;&nbsp;&nbsp;&nbsp;end_time | uint64 | Ending timestamp for this block |
| &nbsp;&nbsp;&nbsp;&nbsp;memory_required | uint64 | Memory required for decompression |
| &nbsp;&nbsp;**Frame Section** | byte[varint_size(frame_uclen) + varint_size(frame_clen) + varint_size(frame_maxhandle) + frame_clen] | Initial signal values section (3 varints + frame_clen bytes) |
| &nbsp;&nbsp;&nbsp;&nbsp;frame_uclen | varint | Uncompressed frame data size |
| &nbsp;&nbsp;&nbsp;&nbsp;frame_clen | varint | Compressed frame data size |
| &nbsp;&nbsp;&nbsp;&nbsp;frame_maxhandle | varint | Maximum signal handle in frame |
| &nbsp;&nbsp;&nbsp;&nbsp;**frame_data** | byte[frame_clen]→byte[frame_uclen] | Compressed initial signal values (Zlib) |
| &nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;**initial_values** | byte[frame_uclen] | Signal initial states (decompressed content) |
| &nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;**signal_handle_data*** | byte[signal_lens[handle]] | Initial value data per signal handle (0 to frame_maxhandle-1, signal_lens from geometry block) |
| &nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;*single_bit_signal* | byte[1] | Option: For signal_lens[handle]==1: logic state (0='0', 1='1', 2='Z', 3='X', 4='L', 5='H', 6='U', 7='W', 8='D') |
| &nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;*multi_bit_signal* | byte[signal_lens[handle]] | Option: For signal_lens[handle]>1: logic states for each bit position |
| &nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;*real_signal* | byte[8] | Option: For FST_VT_VCD_REAL signals: IEEE 754 double value |
| &nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;*variable_length* | byte[0] | Option: For signal_lens[handle]==0: no initial state data |
| &nbsp;&nbsp;**VC Section** | byte[size(VC Section Header) + size(VC Data Section)] | Value change section (header + per-signal compressed data chunks) |
| &nbsp;&nbsp;&nbsp;&nbsp;**VC Section Header** | byte[varint_size(vc_maxhandle) + 1] | Value change section metadata |
| &nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;vc_maxhandle | varint | Maximum signal handle in VC data |
| &nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;pack_type | uint8 | Compression type ('Z'=Zlib, '4'=LZ4, 'F'=FastLZ) applied to individual signal chunks |
| &nbsp;&nbsp;&nbsp;&nbsp;**VC Data Section** | byte[indx_pos - vc_start - 1]  | Sequential per-signal compressed chunks (indx_pos = block_end - 24 - tsec_clen - 8 - chain_clen (remaining_space), vc_start = after pack_type) |
| &nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;**signal_data_chunks** | byte[indx_pos - vc_start - 1] | Sequential per-signal compressed data chunks (offsets from chain_table) |
| &nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;**signal_chunk[handle]*** | byte[chain_table_lengths[handle]] | Individual signal's compressed data chunk (for each signal handle) |
| &nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;uncompressed_length | varint | Length of decompressed signal data (0 = uncompressed data follows) |
| &nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;**decompressed_signal_data** | byte[uncompressed_length or raw_length] | Final signal change data after decompression |
| &nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;**change_entry*** | variable | Individual signal change record |
| &nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;time_delta | varint | Time offset delta with compressed encoding: For single-bit signals: shcnt = 2 << (vli & 1), then tdelta = vli >> shcnt (shifts by 4 or 8 bits). For multi-bit signals: tdelta = vli >> 1 (shifts by 1 bit). The LSB(s) store additional signal state info. |
| &nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;value_data | byte[] | New signal value. Length depends on signal width AND time_delta encoding: For single-bit signals, if LSBs of time_delta varint contain full state (4 or 8 bits), value_data may be empty. For multi-bit signals, value_data contains the actual signal value bytes. |
| &nbsp;&nbsp;**Chain Section** | byte[size(chain_data) + size(chain_clen)] | Signal data offset table (positioned at indx_pos). Contains raw varint data to create chain_table[] (absolute offsets from vc_start) and chain_table_lengths[] (compressed chunk sizes). Final entry: chain_table[idx] = indx_pos - vc_start. |
| &nbsp;&nbsp;&nbsp;&nbsp;**chain_data** | byte[chain_clen] | Raw signal offset mapping containing delta-encoded offset information as varints |
| &nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;**chain_entry*** | variable | Individual chain table entry (different formats for DYN_ALIAS vs DYN_ALIAS2) |
| &nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;*offset_delta* | varint | Option: If val&1 (DYN_ALIAS): pval += (val>>1), sets chain_table[idx] = pval, chain_table_lengths[pidx] = pval - chain_table[pidx] |
| &nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;*skip_count* | varint | Option: If val&1==0 && val>0 (DYN_ALIAS): skip (val>>1) signals, set chain_table[idx] = 0 |
| &nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;*alias_pair* | varint+varint | Option: If val==0 (DYN_ALIAS): signal has no data, chain_table[idx] = 0, chain_table_lengths[idx] = -next_varint (negative alias ref) |
| &nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;*signed_delta* | svarint | Option: DYN_ALIAS2 format: if LSB set, shval = svarint>>1. If shval>0: offset delta. If shval<0: prev_alias = shval. If shval==0: reuse prev_alias |
| &nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;*skip_run* | svarint | Option: DYN_ALIAS2 format: if LSB clear, skip svarint>>1 signals |
| &nbsp;&nbsp;&nbsp;&nbsp;chain_clen | uint64 | Chain compressed size (located at block_end - 24 - tsec_clen - 8) |
| &nbsp;&nbsp;**Time Section** | byte[size(time_data) + size(Time Header)] | Timestamp data (positioned at block_end - 24 - tsec_clen). Contains delta-compressed time values converted to absolute timestamps. |
| &nbsp;&nbsp;&nbsp;&nbsp;**time_data** | byte[tsec_clen]→byte[tsec_uclen] | Compressed time deltas (Zlib). If tsec_uclen == tsec_clen: uncompressed data. |
| &nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;**time_entries** | byte[tsec_uclen] | Decompressed time delta values |
| &nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;**time_entry*** | varint | Time delta varint (tsec_nitems entries). Processed as: tpval += varint, time_table[i] = tpval (cumulative absolute time) |
| &nbsp;&nbsp;&nbsp;&nbsp;**Time Header** | byte[24] | Time section metadata (last 24 bytes of block) |
| &nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;tsec_uclen | uint64 | Uncompressed time data size |
| &nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;tsec_clen | uint64 | Compressed time data size |
| &nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;tsec_nitems | uint64 | Number of time entries |

##### Alias Decoding example 
 
Raw bytes: [0x05, 0x00, 0x03, 0x09, 0x07]

Decoded:
- 0x05 (val=5, val&1=1): offset_delta = 5>>1 = 2
  → chain_table[0] = 0 + 2 = 2
- 0x00: alias marker
- 0x03: alias target = handle 3  
  → chain_table[1] = 0, chain_table_lengths[1] = -3
- 0x09 (val=9, val&1=1): offset_delta = 9>>1 = 4
  → chain_table[2] = 2 + 4 = 6
- 0x07 (val=7, val&1=1): offset_delta = 7>>1 = 3  
  → chain_table[3] = 6 + 3 = 9

Final lengths calculation:
- chain_table_lengths[0] = 6 - 2 = 4
- chain_table_lengths[1] = -3 (alias)
- chain_table_lengths[2] = 9 - 6 = 3
- chain_table_lengths[3] = final_pos - 9

Result arrays:
- offsets:  [2, 0, 6, 9]
- lengths:  [4, -3, 3, X]
- Handle 1 aliases handle 2 (after resolving -3-1=handle 2)

#### 3. Blackout Blocks (`FST_BL_BLACKOUT`)

**Purpose**: Blackout blocks define time periods during the simulation when signal value dumping was temporarily disabled or suspended. This allows simulation tools to exclude irrelevant time periods or reduce file size by omitting stable periods.

**Position**: After value change blocks
**Use Cases**:
- **Simulation Control**: Mark periods when dump was disabled for performance reasons
- **Power Analysis**: Identify sleep/idle periods in power-aware simulations  
- **Debug Focus**: Exclude initialization or cleanup phases from analysis
- **File Size Optimization**: Skip recording during stable/unchanging periods

**Content Description**:
- **Time Ranges**: Pairs of timestamps marking dump disable/enable transitions
- **Activity Flags**: Boolean indicators of whether dumping was active
- **Transition Events**: Precise moments when dump state changed

**Structure**:
- Block type and length
- Array of time/activity pairs indicating dump enable/disable transitions

#### 4. Geometry Block (`FST_BL_GEOM`)

**Purpose**: The geometry block stores supplemental display and navigation information for waveform viewers. It contains metadata about signal organization, handle assignments, and viewer-specific settings that enhance the user experience without affecting the core signal data.

**Position**: Near end of file (before hierarchy block)
**Content Description**:
- **Handle Assignment**: Maps hierarchy signal definitions to actual handle IDs used in value change blocks
- **Signal Bit Widths**: Confirms the number of bits for each signal (logic signals) or data type (real signals)
- **Display Preferences**: File positions, zoom levels, signal ordering for waveform viewers
- **Navigation Bookmarks**: Saved positions and selections within the simulation timeline
- **Viewer State**: Window layouts, color schemes, and other UI-specific information

**Data Organization**:
- **Signal Mapping**: For each handle (1 to max_handle), stores signal bit width or type indicator
- **Logic Signals**: Non-zero values indicate bit width of digital signals
- **Real Signals**: Zero values indicate 64-bit floating-point signals  
- **Display Data**: Compressed viewer-specific information

**Benefits**:
- **Efficient Signal Access**: Direct handle-to-signal mapping without hierarchy traversal
- **Type Validation**: Confirms signal types match hierarchy definitions
- **Viewer Integration**: Seamless restoration of previous viewing sessions

**Structure**:
- Block type and length
- Compressed geometry data (file positions, display preferences, signal handle assignments, etc.)

#### 5. Hierarchy Block

**Purpose**: The hierarchy block defines the complete signal structure and naming hierarchy of the simulated design. It contains all signal definitions, scope organization, and metadata needed to interpret the handles used in value change blocks. This block is essential for understanding what each signal represents.

**Position**: Last data block in file (placed at end for optimal compression)
**Variants**: 
- `FST_BL_HIER`: Gzip compressed
- `FST_BL_HIER_LZ4`: LZ4 compressed  
- `FST_BL_HIER_LZ4DUO`: Double LZ4 compressed

**Content Description**:
- **Scope Definitions**: Hierarchical structure of modules, tasks, functions, and other design blocks
- **Signal Definitions**: Complete signal metadata including names, types, bit widths, and directions
- **Attribute Annotations**: Additional metadata like enumeration tables, display formats, and comments
- **Design Hierarchy**: Tree structure representing the simulated design's organization
- **Signal Context**: Full path names and scope relationships for each signal

**Data Organization Benefits**:
- **Complete Signal Context**: Full hierarchical names and design structure
- **Type Information**: Signal data types, bit widths, and port directions
- **Scope Navigation**: Hierarchical browsing capabilities for large designs
- **Metadata Preservation**: Original design attributes and annotations

**Entry Types Contained**:
- **Scope Entries (Tag 254)**: Module, task, function, and block definitions
- **Variable Entries (Tags 0-29)**: Signal definitions with full metadata
- **Attribute Entries (Tags 252-253)**: Enumeration tables and display preferences
- **Structural Markers (Tag 255)**: Scope end indicators for tree navigation

**Why Placed Last**:
- **Compression Efficiency**: Complete scope structure compresses better as a unit
- **Reference Resolution**: All signals defined before being referenced in geometry
- **Streaming Compatibility**: Allows writers to build hierarchy incrementally during simulation

**Structure**:
- Block type (1 byte)
- Section length (8 bytes) 
- Uncompressed length (8 bytes)
- Compressed hierarchy data containing:
  - Scope definitions with hierarchical structure
  - Variable/signal definitions with complete metadata
  - Attribute annotations and enumeration tables
  - Design context and naming information

### Reading Flow

The FST format is designed for efficient sequential processing while supporting random access to specific time ranges. The reader processes blocks in the following order:

1. **Read and validate file header** (`FST_BL_HDR`) at offset 0
   - Extract simulation metadata (time bounds, signal counts, file structure)
   - Validate format compatibility using endian test value
   - Allocate data structures based on signal and scope counts
   - Determine expected number of value change blocks

2. **Extract file metadata** for parsing preparation
   - Time range establishes simulation bounds for validation
   - Signal counts enable efficient memory allocation
   - Handle range sets bounds for signal reference validation
   - Section count indicates expected data volume

3. **Process blocks sequentially** until EOF
   - Read block type (1 byte) and section length (8 bytes) for each block
   - Process block content based on type and skip unknown blocks safely
   - Maintain file position tracking to prevent reading beyond boundaries
   - Handle compressed data decompression as needed

4. **Parse hierarchy block last** to obtain complete signal definitions
   - Decompress hierarchy data using appropriate algorithm
   - Build signal name lookup tables and scope tree structure
   - Map signal handles to names and metadata for value change interpretation
   - Validate signal definitions against header metadata

### Compression Types

- **LZ4**: Single-stage LZ4 compression (block type `FST_BL_HIER_LZ4`, pack type '4')
- **LZ4DUO**: Two-stage LZ4 compression (block type `FST_BL_HIER_LZ4DUO`)
- **Zlib**: Standard deflate compression (pack type 'Z')
- **FastLZ**: FastLZ algorithm (pack type 'F')

Compressed data is preceded by uncompressed length information (varint or 8-byte value).

