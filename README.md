# dds-pac-benchmarks-odatix

Hardware implementations for ASIC and FPGA benchmarking of non-iterative Direct Digital Synthesizer (DDS) Phase-to-Amplitude Converter (PAC) architectures in Chisel, automated using ODATIX.

## Overview
This repository delivers complete Chisel HDL implementations, automated verification testbenches, and synthesis benchmarking flows for various DDS architectures. Focused on the Phase-to-Amplitude Converter (PAC), the framework systematically characterizes silicon area, power dissipation, and spectral fidelity across continuous-wave (CW) generation profiles. 

Design-space exploration and hardware metrics (logic area, dynamic power, and maximum operating frequency $F_{\max}$) are automated using ODATIX, targeting a TSMC 28nm HPC+ CMOS process alongside FPGA evaluations.

## Evaluated PAC Architectures
* **Symmetry-Exploiting ROMs:** Full-Wave baseline, Quarter-Wave (QW), and Eighth-Wave (EW) compressions leveraging trigonometric reflections and 3-bit octant decoding to minimize memory footprint.
* **Tierney Decompositions:** Multi-table partitioning architectures utilizing Radix-2 and Radix-4 reconstruction adder trees for coarse and fine phase interpolation.
* **Polynomial Approximations:** Multiplier-based algebraic engines, including 2nd- and 3rd-order Taylor series (12-bit to 18-bit configurations) alongside an optimized 9-cycle pipelined Eighth-Wave Chebyshev polynomial core driven by least-squares derived Q17 fixed-point coefficients.

## Repository Structure
* `src/main/scala/` — Parametric Chisel RTL source files for all DDS/PAC datapaths and phase accumulators.
* `src/test/scala/` — ScalaTest verification frameworks, testbenches, and behavioral golden models.
* `odatix_userconfig/` & `odatix.yml` — Configuration environments and scripts for automated design-space exploration and timing closure.
* `matlab/` *(if applicable)* — Floating-point and fixed-point scripts for spectral profiling (SFDR/SNR) and least-squares coefficient generation.

## Prerequisites and Toolchain
* **Hardware Description:** Scala 2.13.18, Chisel 7.7.0.
* **Build Systems:** SBT.
* **ASIC Flow:** Synopsys Design Compiler, TSMC 28nm HPC+ CMOS standard-cell library. 
* **FPGA Flow:** AMD Xilinx Vivado.
* **Automation:** ODATIX environment for batch synthesis and metric extraction.

## Getting Started

**1. Clone the repository**
```bash
git clone [https://github.com/TskMax/dds-pac-benchmarks-odatix.git](https://github.com/TskMax/dds-pac-benchmarks-odatix.git)
cd dds-pac-benchmarks-odatix
