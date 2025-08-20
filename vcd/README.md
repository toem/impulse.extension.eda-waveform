This folder contains the VCD (Value Change Dump) reader implementation, documentation, and sample data for validation inside the impulse framework.

### Contents

- VcdReader.java
  - The VCD reader implementation that parses VCD files into impulse records, sets up writers (logic/real/text), applies properties (include/exclude, time range, transforms), and integrates with logging/progress.
- vcd-reader.md
  - Reader documentation covering capabilities, properties, implementation details, and known limitations.
- vcd-format.md
  - Reference notes and guidance about the VCD file format as relevant to parsing.
- vcd-reader-test.jx
  - A test scenario/workflow to exercise the reader against the provided sample data.
- samples/
  - Example inputs and reference outputs for quick verification.
  - OVERVIEW.md: Describes the sample collection and intent.
  - ref_build.jx: Reference test/build configuration for running validations.
  - t0001.vcd … t0030.vcd: Input VCD files covering a range of features and edge cases.
  - tXXXX.recMl files: Expected impulse record outputs to compare against imports.
  - Variants like t0002_range.recMl, t0002_select.recMl: Expected outputs for specific property configurations (e.g., time range, include/exclude).
