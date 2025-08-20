<img src="images/eda-waveform.png" alt="EDA Waveform Extension Screenshot" width="196" />

# EDA Waveform Extension for impulse

This package provides an extension for the [impulse](https://www.toem.io/index.php/projects/impulse) framework, focusing on the import and (optionally) export of Electronic Design Automation (EDA) waveform data. impulse is a powerful, extensible platform for signal analysis, visualization, and processing, widely used in engineering and scientific domains to handle a broad range of data formats.

## About impulse

impulse is a modular, open-source framework designed to unify the handling of signal and measurement data from diverse sources. It provides a robust infrastructure for reading, writing, analyzing, and visualizing signals, supporting both simple and highly structured data. impulse's extensible architecture allows developers to add support for new data formats by implementing custom readers and writers, making it a versatile tool for engineers, scientists, and developers.

## About EDA

**Electronic Design Automation (EDA)** refers to the category of software tools used for designing, simulating, and verifying electronic systems such as integrated circuits, FPGAs, and printed circuit boards. EDA tools generate waveform data during simulation and verification processes, which are essential for analyzing digital and mixed-signal designs. Common EDA waveform formats include VCD, FST, FSDB, and WLF, each associated with different simulation tools and workflows.

## Purpose of this Extension

The EDA Waveform Extension integrates support for standard EDA waveform formats into impulse, enabling users to seamlessly import, analyze, and visualize simulation and measurement results from popular EDA tools. This extension is essential for anyone working with digital or mixed-signal simulation data, as it bridges the gap between EDA tool outputs and the advanced analysis capabilities of impulse.

## Supported and Planned Formats

This package currently includes, or is planned to include, readers (and optionally writers) for the following EDA waveform formats:

- **VCD (Value Change Dump):**  
  The industry-standard text-based format for representing digital simulation waveforms, widely supported by Verilog and SystemVerilog simulators.

- **FST (Fast Signal Trace):**  
  A compact, binary waveform format developed by GTKWave, offering efficient storage and fast access for large simulation datasets.  
  _FST support is already implemented in this extension._

- **FSDB (Fast Signal Database):**  
  A high-performance, binary waveform database format used by several commercial EDA tools (e.g., Synopsys VCS, Novas Debussy).  
  _FSDB support is in preparation._

- **WLF (Waveform Log Format):**  
  The native waveform format for Mentor Graphics ModelSim and Questa simulators.  
  _WLF support is in preparation._

Additional formats may be added in the future as the needs of the EDA and impulse communities evolve.

## Getting Started

To use this extension, install it into your impulse-enabled environment. Once installed, you can import supported EDA waveform files directly into impulse for analysis and visualization. For details on implementing or extending readers and writers, refer to the [impulse developer documentation](https://toem.de/index.php/projects/impulse).

## Status

- **VCD:** Supported (reader implemented "stable")
- **FST:** Supported (reader implemented "experimental")
- **FSDB:** In preparation
- **WLF:** In preparation

Contributions and feedback are welcome as this extension continues to evolve.

