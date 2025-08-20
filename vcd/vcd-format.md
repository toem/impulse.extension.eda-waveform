# VCD (Value Change Dump) Format

## Overview

This document focuses on the VCD format and its syntax for representing digital simulation waveforms. Value Change Dump (VCD) is a standardized ASCII text format originally developed for Verilog simulators and widely used to exchange waveform data between EDA tools.

### Key Characteristics

- **Text-based format**: Human-readable ASCII format that can be viewed and edited with standard text editors
- **Industry standard**: Defined by IEEE Std 1364 (Verilog) and widely supported across EDA tools
- **Hierarchical structure**: Supports module hierarchy and signal scoping from HDL designs
- **Multiple data types**: Handles scalar bits, vectors, integers, reals, and strings
- **Time-based**: Records value changes at specific simulation timestamps
- **Compact representation**: Only records changes, not every timestep

### VCD File Structure

A typical VCD file consists of several sections:

1. **Header Section**: Contains metadata about the simulation
   - Date, version, timescale information
   - Variable declarations with scope hierarchy
   - Signal definitions with identifiers and bit widths

2. **Initial Values Section**: Defines initial states of all signals

3. **Value Change Section**: Records chronological changes to signal values
   - Timestamp markers (#timestamp)
   - Value changes using compact notation

## File structure and commands

The following commands and markers are used in VCD files:

- Header and metadata:
  - $date ... $end — optional, human-readable date
  - $version ... $end — optional, tool/version string
  - $timescale <number><unit> $end or $timescale <number> <unit> $end — units: s, ms, us, ns, ps, fs

- Hierarchy and declarations:
  - $scope module <name> $end — open a hierarchical scope (module/task/function may appear)
  - $upscope $end — close the current scope
  - $var <type> <size> <id> <reference> $end — declare a signal
    - type: wire, reg, integer, real, string, event, parameter, supply0/1, tri, etc.
    - size: bit width (1 for scalars)
    - id: short printable token used in value-change records
    - reference: signal name; may include bus indices (e.g., data[7:0])
  - $enddefinitions $end — marks the end of declarations

- Dump control:
  - $dumpvars ... $end — initial dump of current values (often at time 0)
  - $dumpall $end — dump all values at the current time
  - $dumpon $end / $dumpoff $end — enable/disable dumping
  - $comment ... $end — parser-ignored annotation

- Time and value changes (not $-commands):
  - #<time> — advance to time <time> (integer, interpreted per $timescale)
  - Scalar: 0|1|x|X|z|Z followed immediately by <id> (e.g., 1!)
  - Vector: b<binary> <id> (e.g., b1010 ")
  - Real: r<real> <id> (e.g., r3.14 $)
  - String: s<string> <id> (e.g., sRESET %)

- Notes:
  - $timescale uses an integer and a unit (s, ms, us, ns, ps, fs). Examples: 1ns, 10ps.
  - Identifiers are printable, non-whitespace tokens chosen by the generator; they are not required to be valid Verilog identifiers.

## Variable declaration (example)

Variable declarations in VCD specify the signals in the design and their properties. Here is an example:

```vcd
$scope module testbench $end
$var wire 1 ! clk $end
$var wire 8 " data[7:0] $end
$var wire 1 # reset $end
$var real 1 $ voltage $end
$upscope $end
```

This example declares four variables: a 1-bit wire `clk`, an 8-bit wire `data`, a 1-bit wire `reset`, and a 1-bit real-valued signal `voltage`. The `scope` and `upscope` commands define the hierarchical context for the variables.

## Value change syntax

Value changes in VCD are represented with timestamps and compact notation. Here are some examples:

```vcd
#0
#10
1!
#15
b10101010 "
#20
0!
#25
0#
r3.3 $
#30
1!
b11110000 "
#40
0!
```

In this syntax, `#` indicates a timestamp, followed by the time value. Value changes use `0/1/x/z` for scalars, `b<...>` for vectors, `r<...>` for reals, and `s<...>` for strings. Symbols like `!`, `"`, `#`, and `$` in the examples are ID codes assigned to signals (e.g., `!` for `clk`, `#` for `reset`).

### Common Use Cases

- **Simulation result capture**: Primary output format for Verilog/SystemVerilog simulators
- **Verification workflows**: Analysis of testbench results and coverage data
- **Debug and analysis**: Waveform viewing in tools like GTKWave, ModelSim, and Vivado
- **Data exchange**: Portable format for sharing simulation results between tools
- **Regression testing**: Comparing simulation outputs across different tool versions

### Format Limitations

- **File size**: Text format can become large for long simulations with many signals
- **Parse performance**: ASCII parsing is slower than binary formats for very large files
- **Precision**: Limited floating-point precision compared to native simulator formats

## Example VCD

Here is an example of a VCD file:

```vcd
$date
    Mon Oct 14 10:23:45 2024
$end
$version
    xxx Version 2023.3
$end
$timescale 1ns $end
$scope module testbench $end
$var wire 1 ! clk $end
$var wire 8 " data[7:0] $end
$var wire 1 # reset $end
$var real 1 $ voltage $end
$upscope $end
$enddefinitions $end
$dumpvars
0!
b00000000 "
1#
r0.0 $
$end
#0
#10
1!
#15
b10101010 "
#20
0!
#25
0#
r3.3 $
#30
1!
b11110000 "
#40
0!
```
