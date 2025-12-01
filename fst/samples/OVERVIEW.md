
**File types:**

| Format | Full Name | Type | Compression | Tool Origin | Use Case |
|--------|-----------|------|-------------|-------------|----------|
| **VCD** | Value Change Dump | Text-based | Uncompressed | IEEE Standard | Standard waveform exchange format, human-readable |
| **FST** | Fast Signal Trace | Binary | Compressed | GTKWave | High-performance compressed waveform format |
| **recMl** | Record Markup Language | XML-based | Uncompressed | Impulse | Impulse native format with metadata |
| **recMz** | Record Markup Zipped | Binary/Compressed | Compressed | Impulse | Compressed Impulse format for storage efficiency |

**Key Characteristics:**

- **VCD**: Standard format, widely supported, large file sizes
- **FST**: Excellent compression ratio, fast loading, GTKWave optimized
- **recMl**: Rich metadata support, human-readable structure
- **recMz**: Optimized for Impulse tools, best balance of features and size


# FST Overview

| Target File | Scopes | Signals | Signal Types | Domain Base | Vector Groups | Time Start | Time End | Compression |
|-------------|--------|---------|--------------|-------------|---------------|------------|----------|-------------|
| t0001.fst | 2 | 7 | reg | ps | No | 0 | 3980 | lz4 |
| t0002.fst | 68 | 960 | parameter, reg, wire | ps | Yes | 0 | 4392000 | fastlz |
| t0003.fst | 1 | 1 | real | us | No | 0 | 2396 | zlib |
| t0004.fst | 3 | 14 | wire | ps | Yes | 40000 | 40000000 | lz4 |
| t0005.fst | 24 | 779 | real, wire | ps | Yes | 40000 | 38573898016 | fastlz |
| t0006.fst | 1 | 1 | real | ms | No | 0 | 35160 | zlib |
| t0007.fst | 3 | 13 | reg | fs | No | 0 | 20400000000 | lz4 |
| t0008.fst | 11 | 482 | integer, parameter, reg, tri, tri0, tri1, wand, wire | ps | Yes | 0 | 4504000000 | fastlz |
| t0009.fst | 2 | 468 | parameter, reg | ns | No | 0 | 319980 | zlib |
| t0010.fst | 3 | 5 | integer, reg, trireg | N/A | No | 2000 | 8040 | lz4 |
| t0011.fst | 10 | 119 | reg | fs | Yes | 0 | 20000000000 | fastlz |
| t0012.fst | 1 | 1 | real | ps | No | 0 | 40000000000000 | zlib |
| t0013.fst | 1 | 141 | wire | ps | No | 4000 | 5904000 | lz4 |
| t0014.fst | 1 | 1647 | wire | ps | No | 4000 | 92944000 | fastlz |
| t0015.fst | 1 | 6 | wire | ns | No | 0 | 92 | zlib |
| t0016.fst | 1 | 2 | wire | ps | No | 76000000000 | 152000000000 | lz4 |
| t0017.fst | 1 | 1604 | wire | ps | No | 4000 | 640064000 | fastlz |
| t0018.fst | 1 | 10 | reg, wire | ps | No | 0 | 11200 | zlib |
| t0019.fst | 1 | 64 | wire | ps | Yes | 91451512556 | 144851415092 | lz4 |
| t0020.fst | 0 | 12 | event, reg | us | No | 0 | 11300 | fastlz |
| t0021.fst | 1 | 6 | real, wire | us | No | N/A | N/A | zlib |
| t0022.fst | 1 | 1 | reg | s | No | 0 | 40 | lz4 |
| t0023.fst | 1 | 913 | wire | ns | No | 40 | 3712000 | fastlz |
| t0024.fst | 1 | 4 | reg | ns | No | 0 | 41200 | zlib |
| t0025.fst | 22 | 61 | reg, wire | fs | Yes | 0 | 1220000 | lz4 |
| t0026.fst | 2 | 20 | reg, string | ns | No | 40 | 4000 | fastlz |
| t0027.fst | 2 | 24 | reg, string | ns | No | 40 | 10880 | zlib |
| t0028.fst | 2 | 19 | reg, string | ns | No | 20 | 8000 | lz4 |
| t0029.fst | 4426 | 27143 | integer, reg, ire | ps | Yes | 0 | 249600000000000 | fastlz |
| t0030.fst | 1 | 4 | real | ns | No | 4 | 8 | zlib |


## Complete File Format Size Comparison (t000x files)

| File | VCD | FST | recMl | recMz | VCD→FST | VCD→recMz | FST→recMz |
|------|-----|-----|-------|-------|---------|-----------|-----------|
| **t0001** | 3,543 | 913 | 3,379 | 833 | **3.9:1** | **4.3:1** | 1.1:1 |
| **t0002** | 135,994 | 12,375 | 202,177 | 9,537 | **11.0:1** | **14.3:1** | 1.3:1 |
| **t0003** | 18,315 | 1,574 | 9,149 | 1,515 | **11.6:1** | **12.1:1** | 1.0:1 |
| **t0004** | 27,835 | 793 | 16,185 | 885 | **35.1:1** | **31.5:1** | 0.9:1 |
| **t0005** | 59,404 | 5,578 | 204,997 | 5,135 | **10.6:1** | **11.6:1** | 1.1:1 |
| **t0006** | 83,343 | 23,229 | 43,326 | 23,202 | **3.6:1** | **3.6:1** | 1.0:1 |
| **t0007** | 3,579 | 700 | 7,369 | 711 | **5.1:1** | **5.0:1** | 1.0:1 |
| **t0008** | 391,673 | 7,217 | 509,999 | 7,993 | **54.3:1** | **49.0:1** | 0.9:1 |
| **t0009** | 1,185,820 | 22,259 | 769,814 | 24,221 | **53.3:1** | **49.0:1** | 0.9:1 |
| **t0010** | 601 | 663 | 1,528 | 595 | **0.9:1** | **1.0:1** | 1.1:1 |
| **t0011** | 28,906 | 2,520 | 51,952 | 2,885 | **11.5:1** | **10.0:1** | 0.9:1 |
| **t0012** | 552 | 588 | 574 | 510 | **0.9:1** | **1.1:1** | 1.2:1 |
| **t0013** | 34,870 | 7,066 | 44,898 | 3,971 | **4.9:1** | **8.8:1** | 1.8:1 |
| **t0014** | 273,928 | 17,255 | 458,684 | 14,575 | **15.9:1** | **18.8:1** | 1.2:1 |
| **t0015** | 448 | 567 | 1,372 | 530 | **0.8:1** | **0.8:1** | 1.1:1 |
| **t0016** | 298 | 513 | 588 | 476 | **0.6:1** | **0.6:1** | 1.1:1 |
| **t0017** | 2,139,080 | 194,998 | 1,642,437 | 166,193 | **11.0:1** | **12.9:1** | 1.2:1 |
| **t0018** | 1,668 | 842 | 2,957 | 781 | **2.0:1** | **2.1:1** | 1.1:1 |
| **t0019** | 52,011,771 | 4,604,525 | 6,427,807 | 772,052 | **11.3:1** | **67.4:1** | **6.0:1** |
| **t0020** | 2,276 | 707 | 2,547 | 634 | **3.2:1** | **3.6:1** | 1.1:1 |
| **t0022** | 234 | 502 | 349 | 440 | **0.5:1** | **0.5:1** | 1.1:1 |
| **t0023** | 40,839 | 3,191 | 184,600 | 4,018 | **12.8:1** | **10.2:1** | 0.8:1 |
| **t0024** | 8,810 | 961 | 5,501 | 944 | **9.2:1** | **9.3:1** | 1.0:1 |
| **t0025** | 8,044 | 1,163 | 17,586 | 1,109 | **6.9:1** | **7.3:1** | 1.0:1 |
| **t0026** | 2,682 | 1,000 | 5,063 | 973 | **2.7:1** | **2.8:1** | 1.0:1 |
| **t0027** | 12,834 | 1,699 | 9,331 | 1,595 | **7.6:1** | **8.0:1** | 1.1:1 |
| **t0028** | 2,963 | 688 | 3,769 | 650 | **4.3:1** | **4.6:1** | 1.1:1 |
| **t0029** | 1,203,495 | 94,665 | 5,304,517 | 119,727 | **12.7:1** | **10.1:1** | 0.8:1 |
| **t0030** | 377 | 538 | 735 | 500 | **0.7:1** | **0.8:1** | 1.1:1 |

