package de.toem.impulse.extension.eda.waveform.fst;

import java.io.ByteArrayInputStream;
import java.io.EOFException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.GZIPInputStream;
import java.util.zip.Inflater;

import com.occultusterra.compression.FastLZ;

import de.toem.impulse.ImpulseBase;
import de.toem.impulse.cells.record.IRecord;
import de.toem.impulse.extension.eda.waveform.i18n.I18n;
import de.toem.impulse.samples.IFloatSamplesWriter;
import de.toem.impulse.samples.ILogicSamplesWriter;
import de.toem.impulse.samples.ISample;
import de.toem.impulse.samples.domain.TimeBase;
import de.toem.impulse.serializer.AbstractSingleDomainRecordReader;
import de.toem.impulse.serializer.BinaryParseBuffer;
import de.toem.impulse.serializer.IParsingRecordReader;
import de.toem.impulse.usecase.eda.waveform.WaveformVariable;
import de.toem.toolkits.core.Utils;
import de.toem.toolkits.pattern.bundles.Bundles;
import de.toem.toolkits.pattern.element.ICell;
import de.toem.toolkits.pattern.element.serializer.ISerializerDescriptor;
import de.toem.toolkits.pattern.element.serializer.JavaSerializerPreference;
import de.toem.toolkits.pattern.element.serializer.SingletonSerializerPreference.DefaultSerializerConfiguration;
import de.toem.toolkits.pattern.filter.FilterExpression;
import de.toem.toolkits.pattern.ide.ConfiguredConsoleStream;
import de.toem.toolkits.pattern.ide.IConsoleStream;
import de.toem.toolkits.pattern.ide.Ide;
import de.toem.toolkits.pattern.pageable.BytesPageable;
import de.toem.toolkits.pattern.pageable.Pageable;
import de.toem.toolkits.pattern.properties.IPropertyModel;
import de.toem.toolkits.pattern.registry.RegistryAnnotation;
import de.toem.toolkits.pattern.threading.IProgress;
import de.toem.toolkits.utils.serializer.ParseException;
import de.toem.toolkits.utils.text.MultilineText;
import kanzi.IndexedByteArray;
import kanzi.function.LZ4Codec;

/**
 * FST (Fast Signal Trace) Record Reader for the impulse framework.
 *
 * This reader processes FST (Fast Signal Trace) files, which are commonly used in digital design and verification workflows. FST is a compact binary
 * format for storing digital signals and their values over time.
 *
 * The reader currently supports basic block parsing to identify different sections of the FST file: - Header blocks (FST_BL_HDR = 0) - Value Change
 * blocks (FST_BL_VCDATA = 1) - Blackout blocks (FST_BL_BLACKOUT = 2) - Geometry blocks (FST_BL_GEOM = 3) - Hierarchy blocks (FST_BL_HIER = 4 and
 * compressed variants 6, 7)
 */
@RegistryAnnotation(annotation = FstReader.Annotation.class)
public class FstReader extends AbstractSingleDomainRecordReader {

    public static class Annotation extends AbstractSingleDomainRecordReader.Annotation {

        public static final String id = "de.toem.impulse.reader.fst";
        public static final String label = I18n.Serializer_FstReader;
        public static final String description = I18n.Serializer_FstReader_Description;
        public static final String helpURL = I18n.Serializer_FstReader_HelpURL;
        public static final String defaultNamePattern = "\\.fst$,\\.FST$";
        public static final String formatType = "fst";
        public static final String certificate = "YxwDcTBbUGoX55dzJYLYVcwkeYbjTaQ4VhODxCEfY7ExnE2ylazpEwuuq2EVmdJTgxpkFOEmAqkU6uVBl8aJVVrYkwPSzJaFhUr/WoBdVois32cE7YnIMRETtAegBG12pEoaVokZbyfN8n+x6wMQ4GM7T5AZBDPTuIhjJH3o8OxpgsHjUp4vFR3QGmwOna0dETtv1pK8dv2TUx6u5nwdrE3q/eQ9XErX95ADy7yykYWi/pufDW1mXV9ASrb2qXEAysCS9foHYxdCbQ5xNyD2RCkVUgvsd0nrF6SV2WYyXI9zE5/BjAjK+DW00ffZI/tf88GmCj4rYqWeBa9vhrttLfTI1u4UtRBD";
    }

    // ========================================================================================================================
    // Constants
    // ========================================================================================================================
    // Block type identifiers
    // File header block
    private static final int FST_BL_HDR = 0;

    // Value change data block
    private static final int FST_BL_VCDATA = 1;

    // Blackout/dump control block
    private static final int FST_BL_BLACKOUT = 2;

    // Geometry data block
    private static final int FST_BL_GEOM = 3;

    // Hierarchy definition block
    private static final int FST_BL_HIER = 4;

    // Value change with dynamic aliasing
    private static final int FST_BL_VCDATA_DYN_ALIAS = 5;

    // LZ4-compressed hierarchy block
    private static final int FST_BL_HIER_LZ4 = 6;

    // Dual-stage LZ4 compressed hierarchy
    private static final int FST_BL_HIER_LZ4DUO = 7;

    // Enhanced dynamic aliasing format
    private static final int FST_BL_VCDATA_DYN_ALIAS2 = 8;

    // Entire file compression wrapper
    private static final int FST_BL_ZWRAPPER = 254;

    // Skip block placeholder
    private static final int FST_BL_SKIP = 255;

    // ========================================================================================================================
    // Compression Type Constants (independent of FST block types)
    // ========================================================================================================================
    // No compression
    private static final int COMPRESSION_NONE = 0;

    // Zlib/Deflate compression (used in geometry blocks)
    private static final int COMPRESSION_ZLIB = 1;

    // Gzip compression (used in hierarchy blocks)
    private static final int COMPRESSION_GZIP = 2;

    // LZ4 compression (used in hierarchy blocks)
    private static final int COMPRESSION_LZ4 = 3;

    // Dual-stage LZ4 compression (used in hierarchy blocks)
    private static final int COMPRESSION_LZ4DUO = 4;

    // FastLZ compression (used in hierarchy blocks)
    private static final int COMPRESSION_FASTLZ = 5;

    // Header constants
    // Simulation version string length
    private static final int FST_HDR_SIM_VERSION_SIZE = 128;

    // Date string length
    private static final int FST_HDR_DATE_SIZE = 119;

    // Endian test value
    private static final double FST_DOUBLE_ENDTEST = 2.7182818284590452354;

    // File types
    // Verilog simulation
    private static final int FST_FT_VERILOG = 0;

    // VHDL simulation
    private static final int FST_FT_VHDL = 1;

    // Mixed language simulation
    private static final int FST_FT_VERILOG_VHDL = 2;

    // Hierarchy entry types
    // Scope definition
    private static final int FST_HT_SCOPE = 0;

    // End of scope
    private static final int FST_HT_UPSCOPE = 1;

    // Variable/signal definition
    private static final int FST_HT_VAR = 2;

    // Attribute definition start
    private static final int FST_HT_ATTRBEGIN = 3;

    // Attribute definition end
    private static final int FST_HT_ATTREND = 4;

    // Tree structure begin
    private static final int FST_HT_TREEBEGIN = 5;

    // Tree structure end
    private static final int FST_HT_TREEEND = 6;

    // Actual FST file hierarchy entry tags (different from FST_HT_* above)
    // Actual scope tag in file
    private static final int FST_ST_VCD_SCOPE = 254;

    // Actual upscope tag in file
    private static final int FST_ST_VCD_UPSCOPE = 255;

    // Actual attribute begin tag in file
    private static final int FST_ST_GEN_ATTRBEGIN = 252;

    // Actual attribute end tag in file
    private static final int FST_ST_GEN_ATTREND = 253;

    // Scope types
    private static final int FST_ST_VCD_MODULE = 0;

    private static final int FST_ST_VCD_TASK = 1;

    private static final int FST_ST_VCD_FUNCTION = 2;

    private static final int FST_ST_VCD_BEGIN = 3;

    private static final int FST_ST_VCD_FORK = 4;

    private static final int FST_ST_VCD_GENERATE = 5;

    private static final int FST_ST_VCD_STRUCT = 6;

    private static final int FST_ST_VCD_UNION = 7;

    private static final int FST_ST_VCD_CLASS = 8;

    private static final int FST_ST_VCD_INTERFACE = 9;

    private static final int FST_ST_VCD_PACKAGE = 10;

    private static final int FST_ST_VCD_PROGRAM = 11;

    // Variable types (subset)
    private static final int FST_VT_VCD_EVENT = 0;

    private static final int FST_VT_VCD_INTEGER = 1;

    private static final int FST_VT_VCD_PARAMETER = 2;

    private static final int FST_VT_VCD_REAL = 3;

    private static final int FST_VT_VCD_REG = 5;

    private static final int FST_VT_VCD_WIRE = 16;

    private static final int FST_VT_GEN_STRING = 21;

    // Variable directions
    private static final int FST_VD_IMPLICIT = 0;

    private static final int FST_VD_INPUT = 1;

    private static final int FST_VD_OUTPUT = 2;

    private static final int FST_VD_INOUT = 3;

    // Block type name mapping for logging
    private static final Map<Integer, String> BLOCK_TYPE_NAMES = new HashMap<>();

    static {
        BLOCK_TYPE_NAMES.put(FST_BL_HDR, "HEADER");
        BLOCK_TYPE_NAMES.put(FST_BL_VCDATA, "VALUE_CHANGE");
        BLOCK_TYPE_NAMES.put(FST_BL_BLACKOUT, "BLACKOUT");
        BLOCK_TYPE_NAMES.put(FST_BL_GEOM, "GEOMETRY");
        BLOCK_TYPE_NAMES.put(FST_BL_HIER, "HIERARCHY");
        BLOCK_TYPE_NAMES.put(FST_BL_VCDATA_DYN_ALIAS, "VALUE_CHANGE_DYN_ALIAS");
        BLOCK_TYPE_NAMES.put(FST_BL_HIER_LZ4, "HIERARCHY_LZ4");
        BLOCK_TYPE_NAMES.put(FST_BL_HIER_LZ4DUO, "HIERARCHY_LZ4DUO");
        BLOCK_TYPE_NAMES.put(FST_BL_VCDATA_DYN_ALIAS2, "VALUE_CHANGE_DYN_ALIAS2");
        BLOCK_TYPE_NAMES.put(FST_BL_ZWRAPPER, "ZWRAPPER");
        BLOCK_TYPE_NAMES.put(FST_BL_SKIP, "SKIP");
    }

    // 16KB buffer
    private static final int DEFAULT_BUFFER_SIZE = 16 * 1024;

    // ========================================================================================================================
    // Value Token Constants
    // ========================================================================================================================
    static final int TOKEN_NONE = 0xff;

    static final int[] token = new int[256];

    static {
        for (int i = 0; i < 256; i++)
            token[i] = TOKEN_NONE;
        token[0 << 1] = (ISample.STATE_LEVEL_2 << 4) | ISample.STATE_0_BITS;
        token[1 << 1] = (ISample.STATE_LEVEL_2 << 4) | ISample.STATE_1_BITS;

        token[1 | (0 << 1)] = (ISample.STATE_LEVEL_2 << 4) | ISample.STATE_X_BITS;
        token[1 | (1 << 1)] = (ISample.STATE_LEVEL_2 << 4) | ISample.STATE_Z_BITS;
        token[1 | (2 << 1)] = (ISample.STATE_LEVEL_2 << 4) | ISample.STATE_H_BITS;
        token[1 | (3 << 1)] = (ISample.STATE_LEVEL_2 << 4) | ISample.STATE_U_BITS;
        token[1 | (4 << 1)] = (ISample.STATE_LEVEL_2 << 4) | ISample.STATE_W_BITS;
        token[1 | (5 << 1)] = (ISample.STATE_LEVEL_2 << 4) | ISample.STATE_L_BITS;
        token[1 | (6 << 1)] = (ISample.STATE_LEVEL_2 << 4) | ISample.STATE_D_BITS;
        token[1 | (7 << 1)] = (ISample.STATE_LEVEL_2 << 4) | ISample.STATE_UNKNOWN_BITS;

        token['0'] = (ISample.STATE_LEVEL_2 << 4) | ISample.STATE_0_BITS;
        token['1'] = (ISample.STATE_LEVEL_2 << 4) | ISample.STATE_1_BITS;
        token['Z'] = (ISample.STATE_LEVEL_4 << 4) | ISample.STATE_Z_BITS;
        token['z'] = (ISample.STATE_LEVEL_4 << 4) | ISample.STATE_Z_BITS;
        token['X'] = (ISample.STATE_LEVEL_4 << 4) | ISample.STATE_X_BITS;
        token['x'] = (ISample.STATE_LEVEL_4 << 4) | ISample.STATE_X_BITS;
        token['L'] = (ISample.STATE_LEVEL_16 << 4) | ISample.STATE_L_BITS;
        token['l'] = (ISample.STATE_LEVEL_16 << 4) | ISample.STATE_L_BITS;
        token['H'] = (ISample.STATE_LEVEL_16 << 4) | ISample.STATE_H_BITS;
        token['h'] = (ISample.STATE_LEVEL_16 << 4) | ISample.STATE_H_BITS;
        token['U'] = (ISample.STATE_LEVEL_16 << 4) | ISample.STATE_U_BITS;
        token['u'] = (ISample.STATE_LEVEL_16 << 4) | ISample.STATE_U_BITS;
        token['W'] = (ISample.STATE_LEVEL_16 << 4) | ISample.STATE_W_BITS;
        token['w'] = (ISample.STATE_LEVEL_16 << 4) | ISample.STATE_W_BITS;
        token['-'] = (ISample.STATE_LEVEL_16 << 4) | ISample.STATE_D_BITS;
        token['?'] = (ISample.STATE_LEVEL_16 << 4) | ISample.STATE_UNKNOWN_BITS;
    }

    // ========================================================================================================================
    // Instance Variables
    // ========================================================================================================================
    // Handle assignment tracking (mirrors C implementation)
    // Current handle counter hierachy parsing
    private long currentHierarchyHandle = 0;

    // Current geometry handle position for multiple geometry blocks
    private long currentGeometryHandle = 0;

    // Current frame handle position for frame data parsing
    private long currentFrameHandle = 0;

    // Waveform variables array for signal storage (indexed by handle ID)
    private FstVariable[] waveformVariables;

    // Storage for compressed/uncompressed CBOR transaction chunks
    public Pageable<byte[]> dataBlocks;

    // Console for logging throughout the parsing process
    private IConsoleStream console;

    // Header parsing state
    private boolean headerParsed = false;

    // Header fields (from FST file header block)
    private long startTime;

    private long endTime;

    private boolean littleEndian;

    private long memoryUsed;

    private long numScopes;

    private long numVars;

    private long maxHandle;

    private long sectionCount;

    private byte timescale;

    private String simVersion;

    private String dateString;

    private int fileType;

    private long timezero;

    // Filtering and configuration options
    private boolean keepEmptyScopes;

    private List<FilterExpression> includeSignals;

    private List<FilterExpression> excludeSignals;

    private long start = Long.MIN_VALUE;

    private long end = Long.MAX_VALUE;

    // private long delay = 0;
    // private double scale = 1;
    // ========================================================================================================================
    // FST Variable Class
    // ========================================================================================================================
    /**
     * FST-specific variable class that extends WaveformVariable. Simple implementation with default constructor only.
     */
    public class FstVariable extends WaveformVariable<Integer> {

        boolean disabled;

        // chunk data
        int chunkOffset;

        int chunkLength;

        List<Integer> aliases;

        byte[] states;

        byte[] idata;

        /**
         * Default constructor
         */
        public FstVariable() {
            super();
        }

        /**
         * Write initial value for this variable from frame data and log it.
         *
         * @param frameData
         *            The frame data bytes
         * @param offset
         *            Starting offset in the frame data
         * @param length
         *            Number of bytes to read (should match this variable's scale)
         * @param console
         *            Console stream for logging
         * @param handle
         *            Handle ID for logging purposes
         */
        public void setInitialValue(byte[] data, int pos, int length) {
            // console.log("setInitialValue", name, (char)data[0]);
            // if (length == 1 && data[pos] == 'x')
            // data[pos+0] = 'u';
            if (length > 0 && data != null && pos >= 0 && (pos + length) <= data.length) {
                idata = new byte[length];
                System.arraycopy(data, pos, idata, 0, length);
            }
        }

        public void assertInitialValue() throws ParseException {
            // intialiaze frame data
            if (this.idata != null) {
                byte[] idata = this.idata;
                this.idata = null; // reset idata to avoid reusing it
                writeChange(startTime, false, idata, 0, idata.length);
            }
        }

        /**
         * Add a value change for a 1-bit signal
         */
        public void writeChange1Bit(long timestamp, byte data) throws ParseException {
            // console.log("writeChange1Bit", name, timestamp, data); }
            int t = token[data];
            if (t == TOKEN_NONE)
                throw new ParseException("Invalid logic vector state: " + data);

            // intialiaze frame data
            if (this.idata != null) {
                byte[] idata = this.idata;
                this.idata = null; // reset idata to avoid reusing it
                if (timestamp > startTime) {
                    writeChange(startTime, false, idata, 0, idata.length);
                }
            }

            byte state = (byte) (t & 0xf);
            int level = t >> 4;
            boolean tag = state == ISample.STATE_X_BITS;
            if (aliases != null)
                for (int alias : aliases) {
                    FstVariable var = waveformVariables[alias];
                    if (var != null && var.writer instanceof ILogicSamplesWriter) {
                        ((ILogicSamplesWriter) var.writer).write(timestamp, false, level, state);
                    }
                }
            else if (writer instanceof ILogicSamplesWriter)
                ((ILogicSamplesWriter) writer).write(timestamp, false, level, state);
        }

        /**
         * Add a value change for a multi-bit logic signal
         */
        public void writeChange(long timestamp, boolean bitData, byte[] data, int pos, int length) throws ParseException {
            // intialiaze frame data
            if (this.idata != null) {
                byte[] idata = this.idata;
                this.idata = null; // reset idata to avoid reusing it
                if (timestamp > startTime) {
                    writeChange(startTime, false, idata, 0, idata.length);
                }
            }

            // console.log("writeChange", name, timestamp, bitData, pos, length);
            if (dataType == ISample.DATA_TYPE_LOGIC) {
                if (states == null)
                    states = new byte[scale];
                boolean tag = false;
                // in case of bitData, states are encoded as bits
                if (bitData) {
                    int n = 0;
                    int byteIndex = pos;
                    int bitsLeft = scale;
                    while (bitsLeft > 0) {
                        int b = data[byteIndex] & 0xFF;
                        int bitsInThisByte = Math.min(8, bitsLeft);
                        for (int bit = 0; bit < bitsInThisByte; bit++) {
                            // Extract bit from MSB to LSB
                            states[n++] = (byte) ((b >> (7 - bit)) & 0x01);
                        }
                        byteIndex++;
                        bitsLeft -= bitsInThisByte;
                    }
                } else {
                    int n = 0;
                    for (int i = pos; i < pos + length; i++) {
                        int t = token[data[i]];
                        if (t == TOKEN_NONE)
                            throw new ParseException("Invalid logic vector state: " + data[i]);
                        byte state = (byte) (t & 0xf);
                        states[n++] = state;
                        tag = tag || (state == ISample.STATE_X_BITS);
                    }
                }
                if (aliases != null)
                    for (int alias : aliases) {
                        FstVariable var = waveformVariables[alias];
                        if (var != null && var.writer instanceof ILogicSamplesWriter) {
                            ((ILogicSamplesWriter) var.writer).write(timestamp, tag, (byte) ISample.STATE_0_BITS, states, 0, scale);
                        }
                    }
                else if (writer instanceof ILogicSamplesWriter)
                    ((ILogicSamplesWriter) writer).write(timestamp, tag, (byte) ISample.STATE_0_BITS, states, 0, scale);

            } else if (dataType == ISample.DATA_TYPE_FLOAT) {
                // For floating-point signals, we expect 8 bytes (double precision)
                // convert byte array to long and then to double
                if (length == 8 && (pos + 8) <= data.length) {
                    long bits = 0;
                    if (!littleEndian) {
                        // Big-endian (FST file default)
                        for (int i = 0; i < 8; i++) {
                            bits = (bits << 8) | (data[pos + i] & 0xFF);
                        }
                    } else {
                        // Little-endian (optional, if configured)
                        for (int i = 7; i >= 0; i--) {
                            bits = (bits << 8) | (data[pos + i] & 0xFF);
                        }
                    }
                    double value = Double.longBitsToDouble(bits);
                    // console.log("writeChange", name, timestamp, bits, value);
                    if (writer != null) {
                        ((IFloatSamplesWriter) writer).write(timestamp, false, value);
                    }
                }

            } else {
                throw new ParseException("Unsupported data type for writeChange: " + dataType);
            }
        }
    }

    // ========================================================================================================================
    // Streams
    // ========================================================================================================================
    // ========================================================================================================================
    // Constructors
    // ========================================================================================================================
    /**
     * Default constructor for the FstReader.
     */
    public FstReader() {
        super();
    }

    /**
     * Fully parameterized constructor for the FstReader.
     *
     * @param descriptor
     *            The serializer descriptor providing contextual information
     * @param contentName
     *            The name of the content being processed (e.g., file name)
     * @param contentType
     *            The MIME type or other format descriptor of the content
     * @param cellType
     *            The type of cell that will be produced
     * @param configuration
     *            Configuration name for specialized settings
     * @param properties
     *            Additional properties as key-value pairs
     * @param in
     *            The input stream containing the FST data to be read
     */
    public FstReader(ISerializerDescriptor descriptor, String contentName, String contentType, String cellType, String configuration,
            String[][] properties, InputStream in) {
        super(descriptor, configuration, properties, getPropertyModel(descriptor, null), in);
    }

    // ========================================================================================================================
    // Support Interface
    // ========================================================================================================================
    /**
     * Checks if the reader supports the given request and context.
     *
     * @param request
     *            The request type.
     * @param context
     *            The context type.
     * @return True if supported, false otherwise.
     */
    public static boolean supports(Object request, Object context) {
        int ir = request instanceof Integer ? ((Integer) request).intValue() : -1;
        if (SUPPORT_CONFIGURATION == ir && DefaultSerializerConfiguration.TYPE.equals(context))
            return true;
        return ir == (ir & (SUPPORT_PROPERTIES | SUPPORT_SOURCE));
    }

    /**
     * Create Java serializer preference cell for this reader.
     *
     * This factory method returns an ICell describing the Java preference for the serializer (used in UI/preferences). It configures label, help,
     * pattern and certificate and points to the implementation bundle.
     *
     * @return configured ICell instance for Java serializer preference
     */
    public static ICell createJavaPreference() {
        try {
            JavaSerializerPreference p = new JavaSerializerPreference();
            p.setName(Annotation.label);
            p.description = Annotation.description;
            p.helpUrl = Annotation.helpURL;
            p.namePattern = Annotation.defaultNamePattern;
            p.formatType = Annotation.formatType;
            p.certificate = Annotation.certificate;
            p.impl = MultilineText.toXml(Bundles.getBundleSourceEntryAsString(FstReader.class));
            p.javaBundle = Utils.commarize(ImpulseBase.BUNDLE_ID, Bundles.getBundleId(FstReader.class));
            p.cellType = IRecord.Record.TYPE;
            return p;
        } catch (Throwable e) {
        }
        return null;
    }

    // ========================================================================================================================
    // Property Model
    // ========================================================================================================================
    /**
     * Creates and returns the property model for configuring this reader.
     *
     * @param object
     *            The serializer descriptor, used to provide context
     * @param context
     *            Additional context information
     * @return The property model containing all configurable properties for this reader
     */
    static public IPropertyModel getPropertyModel(ISerializerDescriptor object, Object context) {
        return IParsingRecordReader.getPropertyModel(PROP_DOMAIN_BASE).add(ConfiguredConsoleStream.getPropertyModel());
    }

    // ========================================================================================================================
    // Format Detection
    // ========================================================================================================================
    /**
     * Determines if this reader can process the specified input based on the file name and content type.
     *
     * @param name
     *            The name of the file or content
     * @param contentType
     *            The MIME type or other format descriptor
     * @return APPLICABLE if this reader can process the input, NOT_APPLICABLE otherwise
     */
    @Override
    protected int isApplicable(String name, String contentType) {
        // Check if file has FST extension
        if (name != null && name.toLowerCase().endsWith(".fst")) {
            return APPLICABLE;
        }
        return NOT_APPLICABLE;
    }

    // ========================================================================================================================
    // Parser Implementation
    // ========================================================================================================================
    /**
     * Parses the input stream and creates a record with FST signal data.
     *
     * This implementation reads the FST file header and then processes the blocks to identify different sections of the FST file. As a minimal
     * implementation, it: 1. Reads and validates the FST file header 2. Extracts metadata like timescale, start/end times, etc. 3. Identifies and
     * logs block types and sizes
     *
     * @param progress
     *            Interface for reporting progress and checking for cancellation
     * @param in
     *            The input stream containing the FST data
     * @throws ParseException
     *             If an error occurs during parsing
     */
    @Override
    protected void parse(IProgress progress, InputStream in) throws ParseException {
        BinaryParseBuffer reader = null;
        try {
            // Set up console logging
            console = new ConfiguredConsoleStream(Ide.DEFAULT_CONSOLE, getLabel(), ConfiguredConsoleStream.logging(getProperties()));
            console.info("FST Reader initialized - parsing file");
            // Wrap input stream in BinaryDecoder for all binary access
            reader = new BinaryParseBuffer(in, DEFAULT_BUFFER_SIZE);
            parsePhase1(reader);
            // Create an empty record with nanosecond time base (default for FST files)
            TimeBase base = TimeBase.valueOf(TimeBase.s.ordinal() + timescale);
            initRecord("FST Data", base);
            if (waveformVariables != null) {
                // identify groups
                Map<ICell, List<WaveformVariable<Integer>>> varsByScope = new LinkedHashMap<>();
                // iterate over
                for (int handle = 1; handle < waveformVariables.length; handle++) {
                    FstVariable var = waveformVariables[handle];
                    if (var != null && var.scope != null)
                        varsByScope.computeIfAbsent(var.scope, k -> new ArrayList<>()).add(var);
                }
                WaveformVariable.identifyGroups(varsByScope, false);
                WaveformVariable.createSignals(varsByScope, getRoot(), base, this.includeSignals, this.excludeSignals);
                WaveformVariable.createWriters(varsByScope, base, this);

                // Notify that record structure has been created
                changed(CHANGED_RECORD);
                // Initialize at position 0
                open(startTime);
                changed(CHANGED_CURRENT, 0);
                parsePhase2();
                // Close the record
                close(endTime);
            } else
                throw new ParseException("No variables found");
        } catch (Throwable e) {
            throw new ParseException("Error in FST reader: " + e.getMessage(), e);
        } finally {
            try {
                if (in != null) {
                    in.close();
                }
            } catch (Exception e) {
                // Ignore exceptions on close
            }
        }
    }

    void parsePhase1(BinaryParseBuffer reader) throws ParseException {
        // Parse FST file blocks sequentially
        int blockCount = 0;
        console.info("=== Starting FST Block Parsing ===");
        try {
            while (reader.hasMoreData()) {
                // Read block type
                int blockType = reader.getIntBE(1, false);
                blockCount++;
                String blockTypeName = BLOCK_TYPE_NAMES.getOrDefault(blockType, "UNKNOWN");
                console.info("Block #", blockCount, ": Type=0x", Integer.toHexString(blockType), " (", blockTypeName, ")");
                // Check if this is a VCDATA block (for special handling)
                boolean isVcdataBlock = (blockType == FST_BL_VCDATA || blockType == FST_BL_VCDATA_DYN_ALIAS || blockType == FST_BL_VCDATA_DYN_ALIAS2);
                boolean isZwrapperBlock = (blockType == FST_BL_ZWRAPPER);
                // Read section length (common to all block types except some special cases)
                long sectionLength;
                byte[] blockData;
                try {
                    // Read section length (big-endian, includes the 8 bytes of section length itself)
                    sectionLength = reader.getLongBE(8, false);
                    console.info("  Section length:", sectionLength, "bytes");
                    // Calculate remaining data size (exclude the 8-byte section length we just read)
                    long dataSize = sectionLength - 8;
                    if (dataSize < 0 || dataSize > Integer.MAX_VALUE) {
                        throw new ParseException("Invalid section length: " + sectionLength);
                    }
                    if (isVcdataBlock) {
                        // For VCDATA blocks: create array with type + section length + payload
                        // 1 byte type + 8 bytes section length + payload
                        byte[] fullBlock = new byte[9 + (int) dataSize];
                        // Set the first 9 bytes to type and section length
                        fullBlock[0] = (byte) blockType;
                        // Write section length in big-endian format (8 bytes)
                        for (int i = 0; i < 8; i++) {
                            fullBlock[1 + i] = (byte) ((sectionLength >>> (8 * (7 - i))) & 0xFF);
                        }
                        // Read the payload directly into the array starting at position 9
                        reader.getBytes(fullBlock, 9, (int) dataSize);
                        console.info("  Read VCDATA block: 1 byte type + 8 bytes length + ", dataSize, " bytes payload = ", fullBlock.length,
                                " total bytes");
                        // Store the complete block for later processing
                        // Time range will be determined in phase 2
                        addDataBlock(fullBlock, 0, 0);
                        console.info("  Stored VCDATA block for phase 2 processing");
                        // Skip to next block
                        continue;
                    } else if (isZwrapperBlock) {
                        // For ZWRAPPER blocks: read entire block including type and length
                        parseZWrapperBlock(reader, dataSize);
                        continue;
                    } else {
                        // For other blocks: read payload only (existing logic)
                        blockData = new byte[(int) dataSize];
                        reader.getBytes(blockData);
                        console.info("  Read", blockData.length, "bytes of block data");
                    }
                } catch (Exception e) {
                    throw new ParseException("Failed to read header block: " + e.getMessage(), e);
                }
                // Create a new BinaryDecoder for this block's data
                // BinaryDecoder blockReader = new BinaryDecoder(blockData);
                BinaryParseBuffer parseBuffer = new BinaryParseBuffer(blockData);
                // Dispatch to specific block handler with isolated block data
                try {
                    switch (blockType) {
                    case FST_BL_HDR:
                        parseHeaderBlock(parseBuffer);
                        break;
                    case FST_BL_BLACKOUT:
                        parseBlackoutBlock(parseBuffer);
                        break;
                    case FST_BL_GEOM:
                        parseGeometryBlock(parseBuffer);
                        break;
                    case FST_BL_HIER:
                    case FST_BL_HIER_LZ4:
                    case FST_BL_HIER_LZ4DUO:
                        parseHierarchyBlock(parseBuffer, blockType);
                        break;
                    case FST_BL_SKIP:
                        parseSkipBlock(parseBuffer);
                        break;
                    default:
                        console.info("  WARNING: Unknown block type, skipped", blockData.length, "bytes");
                        break;
                    }
                } finally {
                    try {
                        // blockReader.close();
                    } catch (Exception e) {
                        // Ignore close errors
                    }
                }
            }
        } catch (EOFException e) {
            console.info("=== End of file reached. Processed", blockCount, "blocks ===");
        }
    }

    /**
     * Adds a transaction chunk to this stream. In lazy mode, this could store the chunk for later processing.
     */
    public void addDataBlock(byte[] block, long startTime, long endTime) {
        // Initialize the chunks pageable if it doesn't exist
        if (dataBlocks == null)
            dataBlocks = new BytesPageable();
        dataBlocks.set(dataBlocks.addFragment(), block);
    }

    void parsePhase2() throws ParseException {
        if (dataBlocks == null || dataBlocks.size() == 0) {
            console.info("=== Phase 2: No VCDATA blocks to process ===");
            return;
        }
        console.info("=== Phase 2: Processing", dataBlocks.size(), "stored VCDATA blocks ===");
        try {
            for (int i = 0; i < dataBlocks.getFragmentCount(); i++) {
                byte[] storedBlock = dataBlocks.get(i);
                if (storedBlock == null || storedBlock.length < 9)
                    throw new ParseException("  Invalid stored block at index" + i + "- skipping");
                // Create a BinaryDecoder for the stored block
                BinaryParseBuffer blockReader = new BinaryParseBuffer(storedBlock);
                try {
                    // Process the value change block
                    parseValueChangeBlock(blockReader);
                } catch (Exception e) {
                    throw new ParseException("Failed to process VCDATA block " + i + ": " + e.getMessage(), e);
                } finally {

                }
            }
        } catch (Exception e) {
            throw new ParseException("Error in Phase 2: " + e.getMessage(), e);
        }
        console.info("=== Phase 2 completed ===");
    }

    // ========================================================================================================================
    // Parse FST header block (always first block)
    // ========================================================================================================================
    /**
     * Parse the FST header block to extract metadata and initialize waveform variables.
     *
     * @param buffer
     *            BinaryParseBuffer for reading the header block data
     * @throws ParseException
     *             If an error occurs during parsing or if header is parsed twice
     */
    private void parseHeaderBlock(BinaryParseBuffer buffer) throws ParseException, EOFException {
        console.info("parseHeaderBlock");

        // Ensure this is the first and only header block we process
        if (headerParsed)
            throw new ParseException("Header block has already been parsed - duplicate header detected");

        // Validate expected header block size (321 bytes total)
        long sectionLength = buffer.total();
        if (sectionLength != 321)
            throw new ParseException(" Unexpected header block length: " + sectionLength + " (expected 321)");

        // ========================================================================================================================
        // Read FST header fields in exact order as defined in FST specification
        // All multi-byte values are stored in big-endian format regardless of host endianness
        // ========================================================================================================================

        // Offset 0: Simulation start time (8 bytes, big-endian unsigned)
        this.startTime = buffer.getLongBE(8, false);

        // Offset 8: Simulation end time (8 bytes, big-endian unsigned)
        // FST stores end_time - 1, so we add 1 to get the actual end time
        this.endTime = buffer.getLongBE(8, false) + 1;

        // Offset 16: Endianness test value (8 bytes IEEE 754 double)
        // Contains the mathematical constant e (2.7182818284590452354) in host byte order
        // Used only to detect the endianness of the host that created the file
        double endianTest = buffer.getDoubleBE();
        this.littleEndian = (endianTest != FST_DOUBLE_ENDTEST);
        // Note: FST file format itself is always big-endian regardless of host endianness

        // Offset 24: Memory used by writer in bytes (8 bytes, big-endian unsigned)
        this.memoryUsed = buffer.getLongBE(8, false);

        // Offset 32: Total number of scopes in hierarchy (8 bytes, big-endian unsigned)
        this.numScopes = buffer.getLongBE(8, false);

        // Offset 40: Total number of variables/signals (8 bytes, big-endian unsigned)
        this.numVars = buffer.getLongBE(8, false);

        // Offset 48: Maximum handle ID used (8 bytes, big-endian unsigned)
        // Handle IDs are 1-based, so array size will be maxHandle + 1
        this.maxHandle = buffer.getLongBE(8, false);

        // Offset 56: Number of value change sections (8 bytes, big-endian unsigned)
        this.sectionCount = buffer.getLongBE(8, false);

        // Offset 64: Time scale exponent (1 byte signed)
        // Represents power of 10 for time unit (e.g., -9 = nanoseconds, -6 = microseconds)
        this.timescale = buffer.getByte();

        // Offset 65: Simulation tool version string (128 bytes, null-terminated)
        // Usually starts with tool identifier like "GHDL" or "VCS"
        this.simVersion = buffer.getString(FST_HDR_SIM_VERSION_SIZE);

        // Offset 193: Date/time string when file was created (119 bytes, null-terminated)
        this.dateString = buffer.getString(FST_HDR_DATE_SIZE);

        // Offset 312: File type identifier (1 byte unsigned)
        // 0=Verilog, 1=VHDL, 2=Mixed Verilog/VHDL
        this.fileType = buffer.getByte() & 0xFF;

        // Offset 313: Time zero offset (8 bytes, big-endian signed)
        // Offset to be added to all timestamps for absolute time
        this.timezero = buffer.getLongBE(8, false);

        // ========================================================================================================================
        // Log parsed header information for debugging
        // ========================================================================================================================
        console.info("  Section length:", sectionLength);
        console.info("  Header Information:");
        console.info("    Start Time:", startTime);
        console.info("    End Time:", endTime);
        console.info("    Memory Used:", memoryUsed, "bytes");
        console.info("    Number of Scopes:", numScopes);
        console.info("    Number of Variables:", numVars);
        console.info("    Max Handle ID:", maxHandle);
        console.info("    Section Count:", sectionCount);
        console.info("    Timescale:", (byte) timescale);
        console.info("    File Type:", fileType, "(", getFileTypeName(fileType), ")");
        console.info("    Time Zero:", timezero);
        console.info("    Simulation Version: '", simVersion, "'");
        console.info("    Date: '", dateString, "'");
        console.info("  Endianness:", this.littleEndian ? "Little-endian" : "Big-endian");

        // ========================================================================================================================
        // Initialize data structures based on header information
        // ========================================================================================================================

        // Create array to hold all waveform variables indexed by handle ID
        // Size is maxHandle + 1 because handle IDs are 1-based (handle 0 is reserved)
        waveformVariables = new FstVariable[(int) (maxHandle + 1)];
        console.info("  Initialized waveform variables array with size:", maxHandle + 1);

        // Mark header as successfully parsed to prevent duplicate processing
        headerParsed = true;
        console.info("  Header parsing completed successfully");

        // Apply time zero offset to get absolute simulation times
        startTime += timezero;
        endTime += timezero;
    }

    /**
     * Get file type name from numeric value
     */
    private String getFileTypeName(int fileType) {
        switch (fileType) {
        case FST_FT_VERILOG:
            return "Verilog";
        case FST_FT_VHDL:
            return "VHDL";
        case FST_FT_VERILOG_VHDL:
            return "Mixed Verilog/VHDL";
        default:
            return "Unknown";
        }
    }

    // ========================================================================================================================
    // Parse FST blackout block
    // ========================================================================================================================

    /**
     * Parse the FST blackout block to extract blackout/dump control information.
     *
     * @param buffer
     *            BinaryParseBuffer for reading the blackout block data
     * @throws ParseException
     *             If an error occurs during parsing
     * @throws EOFException
     *             If unexpected end of data is encountered during parsing
     */
    private void parseBlackoutBlock(BinaryParseBuffer buffer) throws ParseException, EOFException {
        console.info("parseBlackoutBlock");

        // Get total size of the blackout block data
        long sectionLength = buffer.total();
        console.info("  Section length:", sectionLength, "bytes");

        // ========================================================================================================================
        // Read FST blackout block structure
        // Blackout blocks contain simulation dump control information (e.g., $dumpoff/$dumpon)
        // Format: [num_blackouts:varint] followed by num_blackouts entries
        // Each entry: [activity:1byte] [time_delta:varint]
        // ========================================================================================================================

        // Read the number of blackout entries stored in this block (variable-length encoded)
        long numBlackouts = buffer.parsePlus();
        console.info("  Number of blackouts:", numBlackouts);

        // Validate reasonable blackout count (sanity check to prevent infinite loops)
        if (numBlackouts > 0 && numBlackouts < 10000) {
            console.info("  Blackout entries:");
        }

        // ========================================================================================================================
        // Process blackout entries
        // Each entry represents a change in dump activity at a specific time
        // ========================================================================================================================

        long currentTime = 0; // Accumulative absolute time

        // For performance and log readability, only display first few entries in detail
        long entriesToLog = Math.min(numBlackouts, 5);

        // Read and log the first few blackout entries
        for (int i = 0; i < entriesToLog; i++) {
            // Activity flag: 0 = dump off ($dumpoff), non-zero = dump on ($dumpon)
            int activity = buffer.getByte() & 0xFF;

            // Time delta from previous entry (variable-length encoded)
            // Deltas are relative - must be added to get absolute time
            long timeDelta = buffer.parsePlus();
            currentTime += timeDelta;

            // Log the blackout event with human-readable activity state
            console.info("    Entry", i + 1, ": activity=", (activity != 0 ? "ON" : "OFF"), ", time=", currentTime);
        }

        // If there are more entries, skip them silently to avoid log spam
        if (numBlackouts > entriesToLog) {
            console.info("    ... and", numBlackouts - entriesToLog, "more entries");

            // Efficiently skip remaining blackout entries without detailed processing
            for (long i = entriesToLog; i < numBlackouts; i++) {
                buffer.getByte(); // Skip activity flag (1 byte)
                buffer.parsePlus(); // Skip time delta (variable-length)
            }
        }

        // ========================================================================================================================
        // Handle any remaining data in the block
        // ========================================================================================================================

        // Check for unexpected trailing data after all blackout entries
        int remainingBytes = buffer.available();
        if (remainingBytes > 0) {
            buffer.skipBytes(remainingBytes);
            console.info("  Skipped", remainingBytes, "bytes of remaining blackout data");
        }
    }

    // ========================================================================================================================
    // Parse FST geometry block
    // ========================================================================================================================
    /**
     * Parse FST geometry block containing signal handle assignments and metadata. The geometry block maps signals from the hierarchy to their actual
     * handle numbers and defines signal types (logic vs. real) and bit widths. This block structure:
     * 
     * <pre>
     * Offset 0-7:   64-bit uncompressed data length (big-endian)
     * Offset 8-15:  64-bit maximum handle ID in this geometry block (big-endian)  
     * Offset 16+:   Compressed or uncompressed geometry data containing:
     *               - Variable-length integers (varints) for each signal
     *               - Value 0: Real (floating-point) signal
     *               - Value 0xFFFFFFFF: Zero-length logic signal
     *               - Other values: Logic signal bit width
     * </pre>
     * 
     * The geometry data may be compressed using zlib compression. Multiple geometry blocks can exist in a single FST file to handle large designs
     * efficiently.
     *
     * @param reader
     *            BinaryParseBuffer positioned at the start of geometry block data
     * @throws ParseException
     *             If an error occurs during parsing or decompression fails
     * @throws EOFException
     *             If unexpected end of data is encountered during parsing
     */
    private void parseGeometryBlock(BinaryParseBuffer reader) throws ParseException, EOFException {
        console.info("parseGeometryBlock");

        // Calculate total section length including the 8-byte section length field that was read in parsePhase1()
        long sectionLength = reader.total() + 8;
        console.info("  Section length:", sectionLength, "bytes");

        // Read geometry block header fields from buffer (FST format uses big-endian encoding for all integers)
        long uncompressedLength = reader.getLongBE(8, false); // Offset 0-7: Size of decompressed geometry data
        long maxHandle = reader.getLongBE(8, false); // Offset 8-15: Highest signal handle ID in this block
        console.info("  Uncompressed length:", uncompressedLength, "bytes");
        console.info("  Max handle:", maxHandle);

        // Calculate actual compressed data size: total section - section length field - header fields
        long compressedDataLength = (sectionLength - 8) - 16; // Subtract 8 (section length) + 16 (two 8-byte header fields)
        boolean isCompressed = compressedDataLength != uncompressedLength;

        if (isCompressed) {
            double compressionRatio = 100.0 * compressedDataLength / uncompressedLength;
            console.info("  Compression ratio:", Math.round(compressionRatio * 100.0) / 100.0, "%");
            console.info("  Compression: Yes (Zlib)");

            if (compressedDataLength > 0) {
                // Read the compressed geometry data byte-by-byte from the BinaryParseBuffer
                byte[] compressedData = new byte[(int) compressedDataLength];
                for (int i = 0; i < compressedDataLength; i++) {
                    compressedData[i] = reader.getByte(); // Sequential byte reads from offset 16+
                }

                // Decompress using zlib algorithm with expected output size validation
                byte[] decompressedData = decompressData(compressedData, COMPRESSION_ZLIB, uncompressedLength);
                if (decompressedData != null) {
                    console.info("  Successfully decompressed geometry data");

                    // Wrap decompressed data in new BinaryParseBuffer for signal definition parsing
                    BinaryParseBuffer geometryReader = new BinaryParseBuffer(decompressedData);

                    // Parse the signal handle assignments and type information from decompressed data
                    parseGeometryData(geometryReader, uncompressedLength, maxHandle);
                    console.info("  Completed geometry data parsing");
                } else {
                    throw new ParseException("Failed to decompress geometry data");
                }
            } else {
                console.info("  No compressed data to process");
            }
        } else {
            console.info("  Compression: None (uncompressed)");

            if (compressedDataLength > 0) {
                // Read uncompressed geometry data directly from buffer (no decompression needed)
                byte[] geometryData = new byte[(int) compressedDataLength];
                for (int i = 0; i < compressedDataLength; i++) {
                    geometryData[i] = reader.getByte(); // Direct byte copy from offset 16+
                }

                // Wrap raw data in BinaryParseBuffer for consistent parsing interface
                BinaryParseBuffer geometryReader = new BinaryParseBuffer(geometryData);

                // Parse the signal definitions directly from uncompressed data
                parseGeometryData(geometryReader, compressedDataLength, maxHandle);
                console.info("  Completed geometry data parsing");
            } else {
                console.info("  No geometry data to process");
            }
        }
    }

    /**
     * Parse decompressed geometry data containing signal handle assignments and metadata. This method processes the core geometry information where
     * signals from the hierarchy get their actual handle numbers assigned and signal types are determined.
     * 
     * <p>
     * The geometry data structure consists of a sequence of variable-length integers (varints) using FST's "plus encoding" format, where each integer
     * encodes signal type and bit width information:
     * </p>
     * 
     * <pre>
     * Value 0:          Real (floating-point) signal (64-bit IEEE 754 double)
     * Value 0xFFFFFFFF: Zero-length logic signal (no actual data bits)
     * Other values:     Logic signal bit width (1, 8, 16, 32, etc.)
     * </pre>
     * 
     * <p>
     * Handle IDs are processed sequentially starting from {@code currentGeometryHandle + 1} and continuing for {@code maxHandle} entries. This allows
     * multiple geometry blocks to collectively define all signals in large designs.
     * </p>
     *
     * @param reader
     *            BinaryParseBuffer containing the decompressed geometry data
     * @param dataLength
     *            Length of the decompressed geometry data in bytes
     * @param maxHandle
     *            Maximum number of signal handles defined in this geometry block
     * @throws ParseException
     *             If an error occurs during geometry data parsing or signal creation
     */
    private void parseGeometryData(BinaryParseBuffer reader, long dataLength, long maxHandle) throws ParseException {
        console.info("  Parsing geometry data (", dataLength, "bytes, max handle:", maxHandle, ")");
        console.info("  Starting from geometry handle:", currentGeometryHandle + 1);

        try {
            int signalsProcessed = 0; // Counter for total signals processed in this geometry block
            int signalsWithData = 0; // Counter for logic signals that have actual data width > 0
            int realSignals = 0; // Counter for floating-point/real number signals

            // Parse signal geometry entries sequentially, continuing from the last geometry handle position
            // FST handle IDs are 1-based indexing, so increment current position to get next handle
            long startHandle = currentGeometryHandle + 1; // First handle ID to process in this block
            long endHandle = currentGeometryHandle + maxHandle; // Last handle ID (inclusive range)

            for (long handle = startHandle; handle <= endHandle && reader.available() > 0; handle++) {
                // Read variable-length integer encoding the signal type and bit width information
                long val = reader.parsePlus(); // FST uses "plus encoding" variant of varint
                signalsProcessed++;

                // Retrieve or create FstVariable instance for this handle ID (handles may be defined across multiple blocks)
                FstVariable fstVar = waveformVariables[(int) handle];
                if (fstVar == null) {
                    // First time encountering this handle - create new variable entry
                    fstVar = new FstVariable();
                    fstVar.handle = (int) handle; // Store the 1-based handle ID

                    // Insert into waveformVariables array using handle as direct index
                    waveformVariables[(int) handle] = fstVar;
                }

                if (val != 0) {
                    // Non-zero value indicates a logic/digital signal with specified bit width
                    signalsWithData++;
                    if (val != 0xFFFFFFFFL) {
                        // Standard logic signal - value represents the bit width (1, 8, 32, etc.)
                        fstVar.scale = (int) val; // Store bit width in scale field
                        fstVar.dataType = ISample.DATA_TYPE_LOGIC; // Mark as digital/logic signal type
                    } else {
                        // Special encoding: 0xFFFFFFFF indicates a zero-length logic signal (no data)
                        fstVar.scale = 0; // Zero bit width

                        // Still classified as logic type for consistency
                        fstVar.dataType = ISample.DATA_TYPE_LOGIC;
                    }
                    console.info("    Handle", handle, ": Logic signal,", fstVar.scale, "bits ");
                } else {
                    // Zero value indicates a real-valued (floating-point) signal
                    realSignals++;
                    fstVar.dataType = ISample.DATA_TYPE_FLOAT; // Mark as floating-point signal type

                    // Note: Real signals use standard 64-bit IEEE 754 double precision
                    console.info("    Handle", handle, ": Real signal, 64-bit float");
                }

                // Log parsing progress every 100 signals to monitor large geometry blocks
                if (signalsProcessed % 100 == 0) {
                    console.info("    ... processed", signalsProcessed, "of", maxHandle, "signals");
                }
            }

            // Update global geometry handle tracker for multi-block geometry parsing
            currentGeometryHandle = endHandle; // Store last handle processed for next geometry block

            console.info("  Geometry parsing completed:");
            console.info("    Total signals processed:", signalsProcessed);
            console.info("    Logic signals (with data):", signalsWithData);
            console.info("    Real signals (floating-point):", realSignals);
            console.info("    Signals without data:", signalsProcessed - signalsWithData - realSignals);
            console.info("    Current geometry handle position:", currentGeometryHandle);

            // Verify expected signal count matches actual parsed count (data integrity check)
            if (signalsProcessed != maxHandle) {
                console.info("    WARNING: Expected", maxHandle, "signals, but processed", signalsProcessed);
            }
        } catch (Exception e) {
            throw new ParseException("Failed to parse geometry data: " + e.getMessage(), e);
        }
    }

    // ========================================================================================================================
    // Parse FST hierarchy block
    // ========================================================================================================================
    /**
     * Parse FST hierarchy block containing design hierarchy structure (scopes and signal definitions). The hierarchy block structure varies by
     * compression type: - FST_BL_HIER: Gzip compressed hierarchy data - FST_BL_HIER_LZ4: LZ4 compressed hierarchy data - FST_BL_HIER_LZ4DUO:
     * Dual-stage LZ4 compressed hierarchy data
     * 
     * Block format: - Offset 0-7: 64-bit uncompressed data length (big-endian) - Offset 8+: Compressed hierarchy data containing scope/variable
     * definitions
     *
     * @param reader
     *            BinaryParseBuffer positioned at the start of hierarchy block data
     * @param blockType
     *            The specific hierarchy block type (FST_BL_HIER, FST_BL_HIER_LZ4, or FST_BL_HIER_LZ4DUO)
     * @throws ParseException
     *             If an error occurs during parsing or decompression
     * @throws EOFException
     *             If unexpected end of data is encountered
     */
    private void parseHierarchyBlock(BinaryParseBuffer reader, int blockType) throws ParseException {
        String blockTypeName = BLOCK_TYPE_NAMES.get(blockType);
        console.info("parseHierarchyBlock");

        // Calculate total section length including the 8-byte section length field that was read in parsePhase1()
        long sectionLength = reader.total() + 8;
        console.info("  Section length:", sectionLength, "bytes");

        // Read hierarchy block header from the block data (FST format uses big-endian encoding for all integers)
        long uncompressedLength = reader.getLongBE(8, false); // Offset 0-7: Size of decompressed hierarchy data
        console.info("  Uncompressed length:", uncompressedLength, "bytes");

        // Calculate compression ratio for logging: (compressed size / uncompressed size) * 100%
        double compressionRatio = 100.0 * ((sectionLength - 8) - 8) / uncompressedLength; // Exclude section length and uncompressed length fields
        console.info("  Compression ratio:", Math.round(compressionRatio * 100.0) / 100.0, "%");

        // Map FST block type to human-readable compression algorithm name for logging
        String compressionType;
        switch (blockType) {
        case FST_BL_HIER:
            compressionType = "Gzip"; // Standard gzip/zlib compression
            break;
        case FST_BL_HIER_LZ4:
            compressionType = "LZ4"; // LZ4 fast compression algorithm
            break;
        case FST_BL_HIER_LZ4DUO:
            compressionType = "LZ4 Dual-stage"; // Two-pass LZ4 compression for better ratio
            break;
        default:
            compressionType = "Unknown";
            break;
        }
        console.info("  Compression type:", compressionType);

        // Calculate actual compressed data size: total section - section length field - uncompressed length field
        long compressedDataLength = (sectionLength - 8) - 8; // Subtract 8 bytes for uncompressed length field
        if (compressedDataLength > 0) {

            // Read the compressed hierarchy data byte-by-byte from the BinaryParseBuffer
            byte[] compressedData = new byte[(int) compressedDataLength];
            for (int i = 0; i < compressedDataLength; i++) {
                compressedData[i] = reader.getByte(); // Sequential byte reads from offset 8+
            }

            // Map FST block type to internal compression algorithm constant for decompression
            int actualCompressionType;
            switch (blockType) {
            case FST_BL_HIER:
                actualCompressionType = COMPRESSION_GZIP; // Use gzip decompressor
                break;
            case FST_BL_HIER_LZ4:
                actualCompressionType = COMPRESSION_LZ4; // Use LZ4 decompressor
                break;
            case FST_BL_HIER_LZ4DUO:
                actualCompressionType = COMPRESSION_LZ4DUO; // Use dual-stage LZ4 decompressor
                break;
            default:
                actualCompressionType = COMPRESSION_GZIP; // Default fallback to gzip
                break;
            }

            // Decompress the hierarchy data using the appropriate algorithm with expected output size validation
            byte[] decompressedData = decompressData(compressedData, actualCompressionType, uncompressedLength);
            if (decompressedData != null) {
                console.info("  Successfully decompressed hierarchy data");

                // Wrap decompressed data in new BinaryParseBuffer for hierarchy structure parsing
                BinaryParseBuffer hierarchyReader = new BinaryParseBuffer(decompressedData);

                // Parse the hierarchy structure (scopes, variables, attributes) from decompressed data
                parseHierarchyData(hierarchyReader, uncompressedLength);
                console.info("  Completed hierarchy data parsing");
            } else {
                throw new ParseException("Failed to decompress hierarchy data");
            }
        } else {
            console.info("  No compressed data to process");
        }
    }

    /**
     * Parse decompressed hierarchy data containing design structure definitions. The hierarchy data consists of a sequence of tagged entries that
     * define: - Scope entries (modules, tasks, functions, etc.) with FST_ST_VCD_SCOPE (254) - Variable entries with FST_VT_* type tags (0-29)
     * containing signal definitions - Scope end markers with FST_ST_VCD_UPSCOPE (255) - Attribute markers with FST_ST_GEN_ATTRBEGIN (252) and
     * FST_ST_GEN_ATTREND (253)
     *
     * @param reader
     *            BinaryParseBuffer for the decompressed hierarchy data
     * @param dataLength
     *            Length of the decompressed hierarchy data in bytes
     * @throws ParseException
     *             If an error occurs during hierarchy parsing or unknown tags are encountered
     */
    private void parseHierarchyData(BinaryParseBuffer reader, long dataLength) throws ParseException {
        console.info("  Parsing hierarchy data (", dataLength, "bytes)");
        ICell scope = this.base; // Start with root scope as current scope context
        int entryCount = 0; // Counter for total hierarchy entries processed
        try {

            // Process hierarchy entries sequentially until all data is consumed
            while (reader.available() > 0) {

                // Read entry type tag (1 byte) that determines the entry format and meaning
                int tag = reader.getByte() & 0xFF; // Ensure unsigned byte interpretation
                entryCount++;

                // FST hierarchy uses specific tag values different from FST_HT_* constants
                // The actual file format uses FST_ST_* scope tags and FST_VT_* variable type tags
                switch (tag) {
                case // FST_ST_VCD_SCOPE (254): Begin new hierarchical scope
                        254:

                    // Parse scope definition and update current scope context
                    scope = parseHierarchyScope(reader, scope, tag);
                    break;
                case // FST_ST_VCD_UPSCOPE (255): End current scope, return to parent
                        255:
                    if (scope != null) {
                        scope = scope.getCellContainer(); // Move up to parent scope
                    }
                    console.info("End scope");
                    break;
                case // FST_ST_GEN_ATTRBEGIN (252): Begin attribute definition
                        252:
                    parseHierarchyAttributeBegin(reader, tag);
                    break;
                case // FST_ST_GEN_ATTREND (253): End attribute definition
                        253:
                    console.info("End attribute");
                    break;
                default:

                    // Check if this is a variable type entry (FST_VT_* values 0-29 for different signal types)
                    if (tag >= 0 && tag <= 29) {

                        // Parse variable/signal definition within current scope
                        parseHierarchyVariable(reader, scope, tag);
                    } else {

                        // Unknown tag encountered - log warning and attempt recovery
                        console.info("    UNKNOWN tag:", tag, "(0x", Integer.toHexString(tag), ")");

                        // Try to read and skip the next byte to attempt recovery from parsing errors
                        if (reader.available() > 0) {
                            int nextByte = reader.getByte() & 0xFF;
                            console.info("      Next byte:", nextByte, "(0x", Integer.toHexString(nextByte), ")");
                        }
                    }
                    break;
                }
            }
            console.info("  Hierarchy parsing completed:", entryCount, "entries processed");
        } catch (Exception e) {
            throw new ParseException("Failed to parse hierarchy data at entry " + entryCount + ": " + e.getMessage(), e);
        }
    }

    /**
     * Parse a scope entry from hierarchy data defining a new hierarchical design scope. Scope entry format (after FST_ST_VCD_SCOPE tag 254): - Offset
     * 0: 1-byte scope type (FST_ST_VCD_MODULE, FST_ST_VCD_TASK, etc.) - Offset 1+: Null-terminated scope name string - Next: Null-terminated scope
     * component/instance name string
     *
     * @param reader
     *            BinaryParseBuffer positioned after the scope tag
     * @param currentScope
     *            Current scope context (parent scope) to create the new scope within
     * @param tag
     *            The scope tag value (254) for logging purposes
     * @return The newly created scope to use as current scope context
     * @throws ParseException
     *             If an error occurs during scope parsing
     * @throws EOFException
     *             If unexpected end of data is encountered
     */
    private IRecord.Scope parseHierarchyScope(BinaryParseBuffer reader, ICell currentScope, int tag) throws ParseException {

        // Read scope type identifier (1 byte) indicating the kind of scope (module, task, function, etc.)
        int scopeType = reader.getByte() & 0xFF; // Ensure unsigned byte interpretation

        // Read null-terminated scope name string (scope identifier in the design hierarchy)
        String scopeName = reader.parseString((char) 0); // -1 indicates null-terminated string

        // Read null-terminated component name string (instance or component name)
        String scopeComponent = reader.parseString((char) 0); // -1 indicates null-terminated string

        // Convert numeric scope type to human-readable name for logging
        String scopeTypeName = getScopeTypeName(scopeType);

        // Create new hierarchical scope within the current scope context
        IRecord.Scope scope = this.addScope(currentScope, scopeName);
        console.info("  Scope: type=", scopeTypeName, ", name='", scopeName, "', component='", scopeComponent, "'");
        return scope; // Return new scope to become the current scope context
    }

    /**
     * Parse a variable entry from hierarchy data defining a signal within the current scope. Variable entry format (after FST_VT_* type tag 0-29): -
     * Offset 0: 1-byte variable direction (FST_VD_INPUT, FST_VD_OUTPUT, FST_VD_INOUT, etc.) - Offset 1+: Null-terminated variable name string - Next:
     * Variable-length integer (varint) indicating bit length/width of the signal - Next: Variable-length integer (varint) indicating handle ID (0 =
     * assign new, >0 = alias existing)
     *
     * @param reader
     *            BinaryParseBuffer positioned after the variable type tag
     * @param currentScope
     *            Current scope context to create the variable within
     * @param varType
     *            The variable type tag value (0-29) indicating signal type (FST_VT_VCD_WIRE, FST_VT_VCD_REG, etc.)
     * @throws ParseException
     *             If an error occurs during variable parsing or handle assignment
     * @throws EOFException
     *             If unexpected end of data is encountered
     */
    private void parseHierarchyVariable(BinaryParseBuffer reader, ICell currentScope, int varType) throws ParseException {

        // Read variable direction identifier (1 byte) indicating signal direction
        int varDirection = reader.getByte() & 0xFF; // Ensure unsigned byte interpretation

        // Read null-terminated variable name string (signal name in the design)
        String varName = reader.parseString((char) 0); // -1 indicates null-terminated string

        // Read variable bit length/width (variable-length integer encoding)
        long varLength = reader.parsePlus(); // FST uses "plus encoding" variant of varint

        // Read variable handle ID (variable-length integer encoding)
        // Handle 0 = assign new sequential handle, >0 = create alias to existing handle
        long varHandle = reader.parsePlus(); // FST uses "plus encoding" variant of varint

        // Convert numeric values to human-readable names for logging
        String varTypeName = getVariableTypeName(varType);
        String varDirectionName = getVariableDirectionName(varDirection);
        console.info("  Var: type=", varTypeName, varType, " direction=", varDirectionName, varDirection, "length=", varLength, " bits, name='",
                varName, "', handle=", varHandle);

        // Determine the actual handle ID to use for this variable
        long actualHandle;
        if (varHandle == 0) {

            // Handle 0 indicates a new variable - assign next sequential handle from hierarchy counter
            currentHierarchyHandle++; // Increment global hierarchy handle counter
            actualHandle = currentHierarchyHandle;
            console.info("    ", "  Assigned handle: ", actualHandle, " (NEW)");
        } else {

            // Non-zero handle indicates an alias to an existing variable - reuse the existing handle
            actualHandle = varHandle;
            console.info("    ", "  Alias to handle: ", varHandle, " (EXISTING)");
        }

        // Validate that the handle ID is within the bounds of the waveformVariables array
        if (actualHandle <= 0 || actualHandle >= waveformVariables.length) {
            throw new ParseException("Handle " + actualHandle + " is out of bounds (array size: " + waveformVariables.length + ")");
        }

        // Retrieve existing FstVariable or create new one for this handle ID
        FstVariable fstVar = waveformVariables[(int) actualHandle];
        if (fstVar == null) {

            // Create new FstVariable instance for this handle (first time encountering this handle)
            fstVar = new FstVariable();
            waveformVariables[(int) actualHandle] = fstVar; // Store in handle-indexed array
            console.info("    ", "  Created new FstVariable for handle: ", actualHandle);
        } else {

            // Reuse existing FstVariable instance (handle aliasing or duplicate definition)
            console.info("    ", "  Using existing FstVariable for handle: ", actualHandle);
        }

        // Parse variable name to extract bit range information if present
        // Format: signal_name[high_bit:low_bit] or signal_name[single_bit]
        varName = varName.replaceAll("\\s+\\[", "["); // Normalize spacing before brackets
        int vec0Idx = varName.lastIndexOf('['); // Find last opening bracket for bit range
        if (vec0Idx > 0) {

            // Extract base signal name without bit range suffix
            fstVar.idxname = varName.substring(0, vec0Idx).trim();
            int dimIdx = varName.indexOf(':', vec0Idx); // Look for colon separator in bit range
            int vec1Idx = varName.indexOf(']', vec0Idx); // Find closing bracket
            if (vec1Idx > 0) {
                if (dimIdx > 0) {

                    // Parse bit range format: signal_name[high:low]
                    fstVar.idx0 = Utils.parseInt(varName.substring(vec0Idx + 1, dimIdx).trim(), -1); // High index
                    fstVar.idx1 = Utils.parseInt(varName.substring(dimIdx + 1, vec1Idx).trim(), -1); // Low index
                } else

                    // Parse single bit format: signal_name[bit]
                    fstVar.idx0 = Utils.parseInt(varName.substring(vec0Idx + 1, vec1Idx).trim(), -1); // High index
            }

            // Ensure idx0 >= idx1 for proper bit range representation (high to low)
            if (fstVar.idx1 > fstVar.idx0) {
                int swap = fstVar.idx0;
                fstVar.idx0 = fstVar.idx1;
                fstVar.idx1 = swap;
            }
        }

        // Populate FstVariable fields with parsed hierarchy information
        fstVar.handle = (int) actualHandle; // Store the assigned handle ID
        fstVar.name = varName; // Store complete variable name with bit range
        fstVar.description = varTypeName; // Store variable type description (wire, reg, etc.)
        fstVar.tags = null; // Tags currently unused
        fstVar.scope = currentScope; // Associate variable with current hierarchical scope
    }

    /**
     * Parse an attribute begin entry from hierarchy data defining additional metadata. Attribute begin entry format (after FST_ST_GEN_ATTRBEGIN tag
     * 252): - Offset 0: 1-byte attribute type identifier - Offset 1: 1-byte attribute sub-type identifier - Offset 2+: Null-terminated attribute name
     * string - Next: Variable-length integer (varint) attribute argument value
     * 
     * Note: FST attributes are currently not supported by this reader implementation and will be parsed but ignored with a warning message.
     *
     * @param reader
     *            BinaryParseBuffer positioned after the attribute begin tag
     * @param tag
     *            The attribute begin tag value (252) for logging purposes
     * @throws ParseException
     *             If an error occurs during attribute parsing
     * @throws EOFException
     *             If unexpected end of data is encountered
     */
    private void parseHierarchyAttributeBegin(BinaryParseBuffer reader, int tag) throws ParseException {

        // Read attribute type identifier (1 byte) indicating the kind of attribute
        int attrType = reader.getByte() & 0xFF; // Ensure unsigned byte interpretation

        // Read attribute sub-type identifier (1 byte) for additional attribute classification
        int subType = reader.getByte() & 0xFF; // Ensure unsigned byte interpretation

        // Read null-terminated attribute name string (attribute identifier)
        String attrName = reader.parseString((char) 0); // -1 indicates null-terminated string

        // Read attribute argument value (variable-length integer encoding)
        long attrArg = reader.parsePlus(); // FST uses "plus encoding" variant of varint

        console.info("ATTR_BEGIN: ", attrName, " (tag=", tag, ", type=", attrType, ", subType=", subType, ", arg=", attrArg, ")");
        console.warning("Attributes are not supported and will be ignored.");
    }

    /**
     * Convert numeric scope type identifier to human-readable scope type name. Maps FST_ST_VCD_* constants to their corresponding scope type
     * descriptions used in Verilog/VHDL design hierarchies.
     *
     * @param scopeType
     *            Numeric scope type identifier from FST hierarchy data
     * @return Human-readable scope type name (e.g., "module", "task", "function")
     */
    private String getScopeTypeName(int scopeType) {
        switch (scopeType) {
        case FST_ST_VCD_MODULE:
            return "module";
        case FST_ST_VCD_TASK:
            return "task";
        case FST_ST_VCD_FUNCTION:
            return "function";
        case FST_ST_VCD_BEGIN:
            return "begin";
        case FST_ST_VCD_FORK:
            return "fork";
        case FST_ST_VCD_GENERATE:
            return "generate";
        case FST_ST_VCD_STRUCT:
            return "struct";
        case FST_ST_VCD_UNION:
            return "union";
        case FST_ST_VCD_CLASS:
            return "class";
        case FST_ST_VCD_INTERFACE:
            return "interface";
        case FST_ST_VCD_PACKAGE:
            return "package";
        case FST_ST_VCD_PROGRAM:
            return "program";
        default:
            return "unknown";
        }
    }

    /**
     * Convert numeric variable type identifier to human-readable variable type name. Maps FST_VT_* constants to their corresponding signal type
     * descriptions used in Verilog/VHDL design definitions (e.g., wire, reg, integer, real).
     *
     * @param varType
     *            Numeric variable type identifier from FST hierarchy data (0-29)
     * @return Human-readable variable type name (e.g., "wire", "reg", "integer")
     */
    private String getVariableTypeName(int varType) {
        switch (varType) {
        // FST_VT_VCD_EVENT
        case 0:
            return "event";
        // FST_VT_VCD_INTEGER
        case 1:
            return "integer";
        // FST_VT_VCD_PARAMETER
        case 2:
            return "parameter";
        // FST_VT_VCD_REAL
        case 3:
            return "real";
        // FST_VT_VCD_REAL_PARAMETER
        case 4:
            return "real_parameter";
        // FST_VT_VCD_REG
        case 5:
            return "reg";
        // FST_VT_VCD_SUPPLY0
        case 6:
            return "supply0";
        // FST_VT_VCD_SUPPLY1
        case 7:
            return "supply1";
        // FST_VT_VCD_TIME
        case 8:
            return "time";
        // FST_VT_VCD_TRI
        case 9:
            return "tri";
        // FST_VT_VCD_TRIAND
        case 10:
            return "triand";
        // FST_VT_VCD_TRIOR
        case 11:
            return "trior";
        // FST_VT_VCD_TRIREG
        case 12:
            return "trireg";
        // FST_VT_VCD_TRI0
        case 13:
            return "tri0";
        // FST_VT_VCD_TRI1
        case 14:
            return "tri1";
        // FST_VT_VCD_WAND
        case 15:
            return "wand";
        // FST_VT_VCD_WIRE
        case 16:
            return "wire";
        // FST_VT_VCD_WOR
        case 17:
            return "wor";
        // FST_VT_VCD_PORT
        case 18:
            return "port";
        // FST_VT_VCD_SPARRAY
        case 19:
            return "sparray";
        // FST_VT_VCD_REALTIME
        case 20:
            return "realtime";
        // FST_VT_GEN_STRING
        case 21:
            return "string";
        // FST_VT_SV_BIT
        case 22:
            return "sv_bit";
        // FST_VT_SV_LOGIC
        case 23:
            return "sv_logic";
        // FST_VT_SV_INT
        case 24:
            return "sv_int";
        // FST_VT_SV_SHORTINT
        case 25:
            return "sv_shortint";
        // FST_VT_SV_LONGINT
        case 26:
            return "sv_longint";
        // FST_VT_SV_BYTE
        case 27:
            return "sv_byte";
        // FST_VT_SV_ENUM
        case 28:
            return "sv_enum";
        // FST_VT_SV_SHORTREAL
        case 29:
            return "sv_shortreal";
        default:
            return "unknown(" + varType + ")";
        }
    }

    /**
     * Convert numeric variable direction identifier to human-readable direction name. Maps FST_VD_* constants to their corresponding signal direction
     * descriptions used in Verilog/VHDL port declarations (input, output, inout, etc.).
     *
     * @param varDirection
     *            Numeric variable direction identifier from FST hierarchy data
     * @return Human-readable direction name (e.g., "input", "output", "inout")
     */
    private String getVariableDirectionName(int varDirection) {
        switch (varDirection) {
        // FST_VD_IMPLICIT
        case 0:
            return "implicit";
        // FST_VD_INPUT
        case 1:
            return "input";
        // FST_VD_OUTPUT
        case 2:
            return "output";
        // FST_VD_INOUT
        case 3:
            return "inout";
        // FST_VD_BUFFER
        case 4:
            return "buffer";
        // FST_VD_LINKAGE
        case 5:
            return "linkage";
        default:
            return "unknown(" + varDirection + ")";
        }
    }

    // ========================================================================================================================
    // Parse FST skip block
    // ========================================================================================================================
    /**
     * Parse FST skip block containing placeholder data that should be ignored. Skip blocks are used as placeholders in FST files and contain no
     * meaningful data - they exist only to maintain proper block structure and alignment.
     *
     * @param buffer
     *            BinaryParseBuffer for reading the skip block data
     * @throws ParseException
     *             If an error occurs during parsing
     * @throws EOFException
     *             If unexpected end of data is encountered
     */
    private void parseSkipBlock(BinaryParseBuffer buffer) throws ParseException, EOFException {
        console.info("parseSkipBlock");

        // Calculate total section length including the 8-byte section length field that was read in parsePhase1()
        long sectionLength = buffer.total() + 8;
        console.info("  Section length:", sectionLength, "bytes");

        // Skip block data (all remaining data in this block should be skipped)
        // Subtract 8 bytes for the section length field that was already read in parsePhase1()
        long dataSize = sectionLength - 8;
        if (dataSize > 0) {

            // Skip all remaining bytes in this block since skip blocks contain no meaningful data
            buffer.skipBytes((int) dataSize);
            console.info("  Skipped", dataSize, "bytes of skip block data");
        } else {
            console.info("  No skip block data to process");
        }
    }

    // ========================================================================================================================
    // Parse FST value change block
    // ========================================================================================================================
    /**
     * Parse a value change block, which contains signal value changes over time. This method handles both standard value change blocks and dynamic
     * aliasing blocks.
     *
     * @param reader
     *            BinaryDecoder for reading the value change block data
     * @throws ParseException
     *             If an error occurs during parsing
     * @throws EOFException
     *             If the end of the file is reached unexpectedly
     */
    private void parseValueChangeBlock(BinaryParseBuffer reader) throws ParseException, EOFException {
        // Read block type and section length from the beginning of the stored block
        int blockType = reader.getIntBE(1, false);
        long sectionLength = reader.getLongBE(8, false);
        String blockTypeName = BLOCK_TYPE_NAMES.get(blockType);
        console.info("---", blockTypeName, "BLOCK ---");
        console.info("  Section length:", sectionLength, "bytes");
        // Read value change block header from the block data
        // All integers in FST files are always big-endian per the reference implementation.
        long startTime = reader.getLongBE(8, false);
        long endTime = reader.getLongBE(8, false);
        long memoryRequired = reader.getLongBE(8, false);
        console.info("  Time range:", startTime, "-", endTime);
        console.info("  Memory required:", memoryRequired, "bytes");
        // Calculate remaining data size after header (33 bytes already read: type + section_length + start_time + end_time + mem_required)
        long remainingDataSize = sectionLength - 33;
        console.info("  Remaining data size:", remainingDataSize, "bytes");
        if (remainingDataSize > 0) {
            try {
                // Parse different value change block types
                switch (blockType) {
                case FST_BL_VCDATA_DYN_ALIAS:
                case FST_BL_VCDATA_DYN_ALIAS2:
                    parseValueChangeDynAliasBlock(reader, remainingDataSize, blockType);
                    break;
                case FST_BL_VCDATA:
                default:
                    parseValueChangeDataSections(reader, remainingDataSize);
                    break;
                }
            } catch (Exception e) {
                throw new ParseException("Failed to parse value change data: " + e.getMessage(), e);
            }
        } else {
            console.info("  No value change data to process");
        }
    }

    // ========================================================================================================================
    // Parse FST value change FST_BL_VCDATA block
    // ========================================================================================================================
    /**
     * Parse value change data sections within a value change block. These sections contain the actual signal value changes organized by time.
     *
     * @param reader
     *            BinaryDecoder for reading the value change data
     * @param dataSize
     *            Size of the remaining data to parse
     */
    private void parseValueChangeDataSections(BinaryParseBuffer reader, long dataSize) throws ParseException, EOFException {
        console.info("  Parsing value change data sections (", dataSize, "bytes)");
        long bytesProcessed = 0;
        int sectionCount = 0;
        try {
            // Value change data is organized in sections with different types
            // Common sections include: frame data, value change data, chain data, time data
            while (bytesProcessed < dataSize && reader.hasMoreData()) {
                sectionCount++;
                int sectionStartPos = reader.pos();
                // Read section header - typically starts with a length or type indicator
                int sectionType = reader.getIntBE(1, false);
                bytesProcessed++;
                console.info("    Section", sectionCount, ": type=0x", Integer.toHexString(sectionType));
                switch (sectionType) {
                case // Frame section
                        0x00:
                    bytesProcessed += parseFrameSection(reader);
                    break;
                case // Value change section
                        0x01:
                    bytesProcessed += parseValueChangeSection(reader);
                    break;
                case // Chain section
                        0x02:
                    bytesProcessed += parseChainSection(reader);
                    break;
                case // Time section
                        0x03:
                    bytesProcessed += parseTimeSection(reader);
                    break;
                default:
                    // Unknown section type - try to skip by reading a length field
                    console.info("    Unknown section type, attempting to skip");
                    long sectionLength = reader.parsePlus();
                    bytesProcessed += BinaryParseBuffer.plusLen(sectionLength) + sectionLength;
                    reader.skipBytes((int) sectionLength);
                    console.info("    Skipped", sectionLength, "bytes");
                    break;
                }
                // Safety check to prevent infinite loops
                int currentPos = reader.pos();
                if (currentPos == sectionStartPos) {
                    throw new ParseException("No progress made in section " + sectionCount + " - possible data corruption");
                }
            }
            console.info("  Value change data parsing completed:", sectionCount, "sections,", bytesProcessed, "bytes processed");
            // Skip any remaining bytes if we didn't process everything
            long remainingBytes = dataSize - bytesProcessed;
            if (remainingBytes > 0) {
                reader.skipBytes((int) remainingBytes);
                console.info("  Skipped", remainingBytes, "remaining bytes");
            }
        } catch (Exception e) {
            throw new ParseException("Failed to parse value change data sections: " + e.getMessage(), e);
        }
    }

    /**
     * Parse a frame section within value change data
     */
    private long parseFrameSection(BinaryParseBuffer reader) throws ParseException, EOFException {
        console.info("      FRAME section");
        long frameLength = reader.parsePlus();
        long timeFrame = reader.parsePlus();
        console.info("        Frame length:", frameLength);
        console.info("        Time frame:", timeFrame);
        // Frame data contains compressed change information
        if (frameLength > 0) {
            reader.skipBytes((int) frameLength);
            console.info("        Skipped", frameLength, "bytes of frame data");
        }
        return BinaryParseBuffer.plusLen(frameLength) + BinaryParseBuffer.plusLen(timeFrame) + frameLength;
    }

    /**
     * Parse a value change section within value change data
     */
    private long parseValueChangeSection(BinaryParseBuffer reader) throws ParseException, EOFException {
        console.info("      VALUE_CHANGE section");
        long changeLength = reader.parsePlus();
        long numChanges = reader.parsePlus();
        console.info("        Change data length:", changeLength);
        console.info("        Number of changes:", numChanges);
        // Value change data contains signal handle and new value pairs
        if (changeLength > 0) {
            reader.skipBytes((int) changeLength);
            console.info("        Skipped", changeLength, "bytes of value change data");
        }
        return BinaryParseBuffer.plusLen(changeLength) + BinaryParseBuffer.plusLen(numChanges) + changeLength;
    }

    /**
     * Parse a chain section within value change data
     */
    private long parseChainSection(BinaryParseBuffer reader) throws ParseException, EOFException {
        console.info("      CHAIN section");
        long chainLength = reader.parsePlus();
        long chainCount = reader.parsePlus();
        console.info("        Chain data length:", chainLength);
        console.info("        Chain count:", chainCount);
        // Chain data links together related value changes
        if (chainLength > 0) {
            reader.skipBytes((int) chainLength);
            console.info("        Skipped", chainLength, "bytes of chain data");
        }
        return BinaryParseBuffer.plusLen(chainLength) + BinaryParseBuffer.plusLen(chainCount) + chainLength;
    }

    /**
     * Parse a time section within value change data
     */
    private long parseTimeSection(BinaryParseBuffer reader) throws ParseException, EOFException {
        console.info("      TIME section");
        long timeLength = reader.parsePlus();
        long timeBase = reader.parsePlus();
        console.info("        Time data length:", timeLength);
        console.info("        Time base:", timeBase);
        // Time data contains timestamp information for value changes
        if (timeLength > 0) {
            reader.skipBytes((int) timeLength);
            console.info("        Skipped", timeLength, "bytes of time data");
        }
        return BinaryParseBuffer.plusLen(timeLength) + BinaryParseBuffer.plusLen(timeBase) + timeLength;
    }

    // ========================================================================================================================
    // Parse FST value change VALUE_CHANGE_DYN_ALIAS block
    // ========================================================================================================================
    /**
     * Parse VALUE_CHANGE_DYN_ALIAS block data.
     *
     * This block uses dynamic aliasing for compression and contains four main sections: 1. Frame section: Initial signal values (compressed with
     * ZLIB) 2. VC section: Compressed signal changes with per-signal chunks 3. Chain section: Signal offset mapping table (at end - 24 - tsec_clen -
     * 8) 4. Time section: Timestamp data (at end - 24)
     *
     * @param reader
     *            BinaryDecoder for reading the block data
     * @param dataSize
     *            Size of the remaining data to parse
     * @param blockType
     *            The specific block type (FST_BL_VCDATA_DYN_ALIAS or FST_BL_VCDATA_DYN_ALIAS2)
     */
    private void parseValueChangeDynAliasBlock(BinaryParseBuffer reader, long dataSize, int blockType) throws ParseException, EOFException {
        console.info("  Parsing dynamic alias value change block (", dataSize, "bytes)");
        int startPos = reader.pos();
        try {
            // ========================================================================================================================
            // Read frame section header
            // ========================================================================================================================
            long frameUclen = reader.parsePlus();
            long frameClen = reader.parsePlus();
            long frameMaxHandle = reader.parsePlus();
            console.info("      Frame section: uclen=", frameUclen, ", clen=", frameClen, ", maxHandle=", frameMaxHandle);
            // Calculate frame header size
            long frameHeaderSize = BinaryParseBuffer.plusLen(frameUclen) + BinaryParseBuffer.plusLen(frameClen)
                    + BinaryParseBuffer.plusLen(frameMaxHandle);
            // Skip frame data for now - process after layout calculation
            int frameDataPos = reader.pos();
            reader.skipBytes((int) frameClen);

            // Read VC section header
            long vcMaxHandle = reader.parsePlus();
            int packType = reader.getIntBE(1, false);
            char packTypeChar = (char) packType;
            String compressionType = getCompressionTypeName(packTypeChar);
            if (!isValidPackType(packTypeChar))
                throw new ParseException("Invalid pack type");
            // Calculate VC header size and capture actual VC data start position
            // +1 for pack type
            long vcHeaderSize = BinaryParseBuffer.plusLen(vcMaxHandle) + 1;
            // Actual position where VC data starts
            long vcDataStartPos = reader.pos();
            // Calculate block layout positions
            // Safety check: ensure we have enough data
            if (reader.total() < 24)
                throw new ParseException("Block too small for time header: " + reader.total() + " bytes");
            console.info("      VC section: maxHandle=", vcMaxHandle, ", packType=", packTypeChar, " (", compressionType, "), headerSize=",
                    vcHeaderSize, ", dataStartPos=", vcDataStartPos);

            // Read time section header from end (last 24 bytes)
            int timeHeaderPos = reader.total() - 24;
            reader.setPos(timeHeaderPos);
            long tsecUclen = reader.getLongBE(8, false);
            long tsecClen = reader.getLongBE(8, false);
            long tsecNitems = reader.getLongBE(8, false);
            long timeDataPos = timeHeaderPos - tsecClen;
            console.info("      Time section: tsecUclen=", tsecUclen, ", tsecClen=", tsecClen, ", tsecNitems=", tsecNitems);

            // Read chain section header (at block_end - 24 - tsec_clen - 8)
            int chainHeaderPos = reader.total() - 24 - (int) tsecClen - 8;
            reader.setPos(chainHeaderPos);
            long chainClen = reader.getLongBE(8, false);
            long chainDataPos = chainHeaderPos - chainClen;
            console.info("      Chain section: chainClen=", chainClen, ", chainDataPos=", chainDataPos);

            long vcDataSize = dataSize - frameHeaderSize - frameClen - vcHeaderSize - 8 - chainClen - 24 - tsecClen;
            console.info("      VC section: vcDataSize=", vcDataSize);

            // ========================================================================================================================
            // Process frame data
            // ========================================================================================================================

            reader.setPos(frameDataPos);
            if (frameClen > 0) {
                byte[] compressedFrameData = new byte[(int) frameClen];
                reader.getBytes(compressedFrameData);
                byte[] frameData = frameClen != frameUclen ? decompressData(compressedFrameData, COMPRESSION_ZLIB, frameUclen) : compressedFrameData;
                if (frameData != null) {
                    // Parse frame initial values inline (refactored from parseFrameInitialValues)
                    console.info("      Parsing initial values for", frameMaxHandle, "signals starting from handle", currentFrameHandle + 1);
                    try {
                        console.info("      Frame data size:", frameData.length, "bytes");
                        int signalsProcessed = 0;
                        int totalExpectedSize = 0;
                        int bytePosition = 0;
                        // Process each signal handle from currentFrameHandle+1 to currentFrameHandle+maxHandle
                        long startHandle = currentFrameHandle + 1;
                        long endHandle = currentFrameHandle + frameMaxHandle;
                        for (long handle = startHandle; handle <= endHandle; handle++) {
                            // Get the FstVariable for this handle to determine size
                            if (handle >= waveformVariables.length) {
                                continue;
                            }
                            FstVariable fstVar = waveformVariables[(int) handle];
                            if (fstVar == null) {
                                continue;
                            }
                            // size in bytes/chars
                            int size = fstVar.dataType == ISample.DATA_TYPE_FLOAT ? 8 : fstVar.scale;
                            if (size <= 0) {
                                continue;
                            }
                            totalExpectedSize += size;
                            // Use the new setInitialValue method to handle this signal
                            fstVar.setInitialValue(frameData, bytePosition, size);
                            // Update byte position for next signal
                            bytePosition += size;
                            signalsProcessed++;
                        }
                        // Update current frame handle position for next frame block
                        currentFrameHandle = endHandle;
                        // Check if total expected size matches actual frame data size
                        console.info("      Frame parsing completed:", signalsProcessed, "signals processed");
                        console.info("      Total expected size:", totalExpectedSize, "bytes");
                        console.info("      Actual frame data size:", frameData.length, "bytes");
                        if (totalExpectedSize != frameData.length)
                            throw new ParseException("Frame parsing size ");
                    } catch (Exception e) {
                        throw new ParseException("Failed to parse frame initial values: " + e.getMessage(), e);
                    }
                } else {
                    throw new ParseException("Failed to decompress frame data");
                }
            }

            // ========================================================================================================================
            // Parse time section to get absolute timestamps
            // ========================================================================================================================
            long[] timestamps = null;
            reader.setPos((int) timeDataPos);
            if (tsecClen > 0) {
                byte[] compressedTimeData = new byte[(int) tsecClen];
                reader.getBytes(compressedTimeData);
                byte[] timeData = decompressData(compressedTimeData, COMPRESSION_ZLIB, tsecUclen);
                if (timeData != null) {
                    BinaryParseBuffer timeBuffer = new BinaryParseBuffer(timeData);
                    timeBuffer.begin();
                    try {
                        // Parse time section data to extract absolute timestamps
                        timestamps = new long[(int) tsecNitems];
                        long currentTime = timezero;
                        for (int i = 0; i < tsecNitems && timeBuffer.available() > 0; i++) {
                            // Read time delta using parsePlus (FST varint format)
                            long timeDelta = timeBuffer.parsePlus();
                            currentTime += timeDelta;
                            timestamps[i] = currentTime;
                        }
                        console.info("      Parsed", timestamps.length, "timestamps:");
                        for (int i = 0; i < Math.min(timestamps.length, 10); i++) {
                            console.info("        Time[", i, "]:", timestamps[i]);
                        }
                        if (timestamps.length > 10) {
                            console.info("        ... and", (timestamps.length - 10), "more timestamps");
                        }
                    } finally {
                        timeBuffer.end();
                    }
                } else {
                    console.info("      Failed to decompress time data");
                }
            }

            // ========================================================================================================================
            // Parse chain section to set offset/length directly in FstVariables
            // ========================================================================================================================

            console.info("      Parsing chain section (", chainClen, "compressed bytes)");
            if (chainDataPos < 0 || chainDataPos + chainClen > reader.total()) {
                console.info("      Chain data position invalid - skipping chain parsing");
            } else {
                // Read chain data
                reader.setPos((int) chainDataPos);
                console.info("      Reading", chainClen, "bytes of chain data from position", chainDataPos);
                byte[] chainData = new byte[(int) chainClen];
                reader.getBytes(chainData);
                console.info("      Read", chainData.length, "bytes of chain data");
                // Log first few bytes of compressed data
                StringBuilder hexLog = new StringBuilder();
                for (int i = 0; i < Math.min(chainData.length, 16); i++) {
                    hexLog.append(String.format("%02X ", chainData[i] & 0xFF));
                }
                console.info("      First", Math.min(chainData.length, 16), "bytes of chain data:", hexLog.toString());
                BinaryParseBuffer chainReader = new BinaryParseBuffer(chainData);
                // Parse chain entries directly into FstVariable offset/length members - optimized for performance
                int idx = 1;
                int pidx = 0;
                long pval = 0;
                // Cache for performance
                final int maxHandle = (int) vcMaxHandle;
                // Cache array reference
                final FstVariable[] vars = waveformVariables;
                if (blockType == FST_BL_VCDATA_DYN_ALIAS2) {
                    // DYN_ALIAS2 format with signed varints - optimized loop
                    long prevAlias = 0;
                    while (chainReader.hasMoreData() && idx <= maxHandle) {
                        // Read signed varint (zigzag encoding)
                        long val = chainReader.parseSPlus();
                        if ((val & 1) != 0) {
                            // LSB set: signed delta
                            // Decode zigzag
                            long shval = val >> 1;
                            if (shval > 0) {
                                // Positive: offset delta - store directly in FstVariable
                                pval += shval;
                                if (idx < vars.length && vars[idx] != null) {
                                    vars[idx].chunkOffset = (int) pval;
                                }
                                if (pidx < vars.length && vars[pidx] != null && idx > 0) {
                                    vars[pidx].chunkLength = (int) (pval - vars[pidx].chunkOffset);
                                }
                                pidx = idx++;
                            } else if (shval < 0) {
                                // Negative: new alias reference
                                if (idx < vars.length && vars[idx] != null) {
                                    vars[idx].chunkOffset = 0;
                                    vars[idx].chunkLength = (int) (prevAlias = shval);
                                }
                                idx++;
                            } else {
                                // Zero: reuse previous alias
                                if (idx < vars.length && vars[idx] != null) {
                                    vars[idx].chunkOffset = 0;
                                    vars[idx].chunkLength = (int) prevAlias;
                                }
                                idx++;
                            }
                        } else {
                            // LSB clear: skip run - bulk operation for performance
                            long skipCount = val >> 1;
                            for (int i = 0; i < skipCount && idx <= maxHandle; i++) {
                                if (idx < vars.length && vars[idx] != null) {
                                    vars[idx].chunkOffset = 0;
                                    vars[idx].chunkLength = 0;
                                }
                                idx++;
                            }
                        }
                    }
                } else {
                    // DYN_ALIAS format with regular varints - optimized loop
                    while (chainReader.hasMoreData() && idx <= maxHandle) {
                        long val = chainReader.parsePlus();
                        if (val == 0) {
                            // Alias pair: val==0 followed by target handle
                            long aliasTarget = chainReader.parsePlus();
                            if (idx < vars.length && vars[idx] != null) {
                                vars[idx].chunkOffset = 0;
                                vars[idx].chunkLength = (int) (-aliasTarget);
                            }
                            idx++;
                        } else if ((val & 1) != 0) {
                            // Offset delta - store directly in FstVariable
                            pval += (val >> 1);
                            if (idx < vars.length && vars[idx] != null) {
                                vars[idx].chunkOffset = (int) pval;
                            }
                            if (pidx < vars.length && vars[pidx] != null && idx > 0) {
                                vars[pidx].chunkLength = (int) (pval - vars[pidx].chunkOffset);
                            }
                            pidx = idx++;
                        } else {
                            // Skip count - bulk operation for performance
                            long skipCount = val >> 1;
                            for (int i = 0; i < skipCount && idx <= maxHandle; i++) {
                                if (idx < vars.length && vars[idx] != null) {
                                    vars[idx].chunkOffset = 0;
                                    vars[idx].chunkLength = 0;
                                }
                                idx++;
                            }
                        }
                    }
                }
                // Set final entry - store end of VC data
                if (pidx < vars.length && vars[pidx] != null && pidx < idx) {
                    vars[pidx].chunkLength = (int) (vcDataSize - vars[pidx].chunkOffset + 2 /* ???? */);
                }
                // Optimized logging - only log summary to avoid performance impact
                console.info("      Chain parsing completed:", idx, "entries processed");
                console.info("      Chain table summary (first 10 entries):");
                for (int i = 0; i < Math.min(idx, 10); i++) {
                    if (i < vars.length && vars[i] != null) {
                        int offset = vars[i].chunkOffset;
                        int length = vars[i].chunkLength;
                        if (offset == 0 && length <= 0) {
                            console.info("        [", i, "]: NO_DATA (offset=0, length=", length, ")");
                        } else if (offset == 0) {
                            console.info("        [", i, "]: ALIAS (offset=0, original_length=", length, ")");
                        } else {
                            console.info("        [", i, "]: DATA (offset=", offset, ", length=", length, ")");
                        }
                    }
                }
                if (idx > 10) {
                    console.info("        ... and", (idx - 10), "more entries");
                }
                // chainReader.close();
                // Populate aliases lists for variables with real data - single pass optimization
                for (int i = 0; i <= maxHandle && i < vars.length; i++) {
                    FstVariable var = vars[i];
                    if (var != null && var.chunkLength < 0) {
                        // This is an alias - add to target's aliases list
                        int targetHandle = Math.abs(var.chunkLength);
                        if (targetHandle < vars.length && vars[targetHandle] != null && vars[targetHandle].chunkOffset >= 0
                                && vars[targetHandle].chunkLength > 0) {
                            FstVariable targetVar = vars[targetHandle];
                            if (targetVar.aliases == null) {
                                targetVar.aliases = new ArrayList<>();
                                // Add self first
                                targetVar.aliases.add(targetHandle);
                            }
                            // Add alias
                            targetVar.aliases.add(i);
                        }
                    }
                }
                // ========================================================================================================================
                // Parse Value Change (VC) Data - Per-signal compressed chunks
                // ========================================================================================================================

                console.info("      Parsing VC data section (", vcDataStartPos, vcDataSize, "bytes)");
                // Use the actual VC data start position captured after reading VC header
                reader.setPos((int) vcDataStartPos);
                // Determine compression type based on pack type
                int signalCompressionType;
                switch (packTypeChar) {
                case 'Z':
                    signalCompressionType = COMPRESSION_ZLIB;
                    break;
                case '4':
                    signalCompressionType = COMPRESSION_LZ4;
                    break;
                case 'F':
                    signalCompressionType = COMPRESSION_FASTLZ;
                    break;
                default:
                    signalCompressionType = COMPRESSION_ZLIB;
                    break;
                }
                if (timestamps != null && timestamps.length > 0) {
                    console.info("      Processing signals with data (offset > 0 && length > 0)");
                    int signalsProcessed = 0;
                    int changesProcessed = 0;
                    // Iterate over variables and process only those with actual data
                    for (int varIdx = 0; varIdx <= maxHandle && varIdx < vars.length; varIdx++) {
                        FstVariable var = vars[varIdx];
                        if (var != null && var.chunkOffset > 0 && var.chunkLength > 0) {
                            signalsProcessed++;
                            console.info("        Processing signal[", varIdx, "]: offset=", var.chunkOffset, ", length=", var.chunkLength);
                            try {
                                // Seek to this signal's data chunk
                                long signalDataPos = vcDataStartPos + var.chunkOffset - 1 /* ??? */;
                                reader.setPos((int) signalDataPos);
                                // Read uncompressed length as varint (first part of the chunk)
                                long uncompressedLength = reader.parsePlus();
                                // Calculate remaining compressed data size
                                int compressedDataSize = var.chunkLength - BinaryParseBuffer.plusLen(uncompressedLength);
                                // Handle compressed vs uncompressed data
                                byte[] decompressedChunk;
                                if (uncompressedLength == 0) {
                                    // No compression - read remaining data directly
                                    decompressedChunk = new byte[compressedDataSize];
                                    reader.getBytes(decompressedChunk);
                                    console.info("          Read", decompressedChunk.length, "bytes of uncompressed data");
                                } else {
                                    // Compressed data - read and decompress
                                    byte[] compressedData = new byte[compressedDataSize];
                                    reader.getBytes(compressedData);
                                    console.info("          Read", compressedData.length, "bytes of compressed data, decompressing to",
                                            uncompressedLength);
                                    decompressedChunk = decompressData(compressedData, signalCompressionType, uncompressedLength);
                                    if (decompressedChunk == null)
                                        throw new ParseException("Failed to decompress signal data");
                                }
                                // Parse individual value changes from decompressed data
                                BinaryParseBuffer chunkReader = new BinaryParseBuffer(decompressedChunk);
                                int valueChangesInThisSignal = 0;
                                int timeIndex = 0;
                                while (chunkReader.hasMoreData()) {
                                    try {
                                        // Read the time/format varint
                                        long vli = chunkReader.parsePlus();
                                        // Determine signal type and parse accordingly (based on C reference fstapi.c)
                                        if (var.scale == 1 && var.dataType == ISample.DATA_TYPE_LOGIC) {
                                            // Case 1: Single-bit signals (0-bit or 1-bit)
                                            // 1-bit signal with 2-state or 4-state values
                                            long shcnt = 2L << (vli & 1);
                                            timeIndex += vli >> shcnt;
                                            var.writeChange1Bit(timestamps[timeIndex], (byte) ((vli & 1) == 0 ? vli & 0x03 : vli & 0x0f));
                                            valueChangesInThisSignal++;
                                        } else if (var.scale == 0) {
                                            // Case 2: Variable-length signals (FST_VT_GEN_STRING, etc.)
                                            timeIndex += vli >> 1;
                                            // Read value length
                                            long valueLength = chunkReader.parsePlus();
                                            // Read value bytes directly from array
                                            int currentPos = chunkReader.pos();
                                            var.writeChange(timestamps[timeIndex], false, decompressedChunk, currentPos, (int) valueLength);
                                            // Skip past the value bytes
                                            chunkReader.skipBytes((int) valueLength);
                                            valueChangesInThisSignal++;
                                        } else if (var.dataType == ISample.DATA_TYPE_LOGIC && var.scale > 1) {
                                            timeIndex += vli >> 1;
                                            // Read value length
                                            long valueLength = var.scale;
                                            boolean bitdata = false;
                                            if ((vli & 1) == 0) {
                                                // Round up to next byte boundary
                                                valueLength = (valueLength + 7) / 8;
                                                bitdata = true;
                                            }
                                            // Read value bytes directly from array
                                            int currentPos = chunkReader.pos();
                                            var.writeChange(timestamps[timeIndex], bitdata, decompressedChunk, currentPos, (int) valueLength);
                                            // Skip past the value bytes
                                            chunkReader.skipBytes((int) valueLength);
                                            valueChangesInThisSignal++;
                                        } else if (var.dataType == ISample.DATA_TYPE_FLOAT) {
                                            int currentPos = chunkReader.pos();
                                            timeIndex += vli >> 1;
                                            var.writeChange(timestamps[timeIndex], false, decompressedChunk, currentPos, 8);
                                            // Skip past the value bytes
                                            chunkReader.skipBytes(8);
                                        }
                                    } catch (Exception e) {
                                        throw new ParseException("Error parsing value change", e);
                                    }
                                }
                                // chunkReader.close();
                                changesProcessed += valueChangesInThisSignal;
                                console.info("          Parsed", valueChangesInThisSignal, "value changes for signal[", varIdx, "]");
                                // Report progress for large datasets
                                if (signalsProcessed % 100 == 0) {
                                    console.info("          Processed", signalsProcessed, "signals so far...");
                                }
                            } catch (Exception e) {
                                console.error("        Error processing signal[", varIdx, "]:", e.getMessage());
                                // Continue with next signal
                            }
                        }
                    }
                    console.info("      VC data parsing completed:");
                    console.info("        Signals with data processed:", signalsProcessed);
                    console.info("        Total value changes processed:", changesProcessed);

                    // Assert frame initialization
                    if (frameClen > 0)
                        for (int varIdx = 0; varIdx <= maxHandle && varIdx < vars.length; varIdx++) {
                            FstVariable var = vars[varIdx];
                            if (var != null)
                                var.assertInitialValue();
                        }
                } else {
                    console.info("      No timestamps available - skipping VC data parsing");
                }
            }
        } catch (Exception e) {
            throw new ParseException("Failed to parse dynamic alias block: " + e.getMessage(), e);
        }
    }

    private String getCompressionTypeName(char packType) {
        switch (packType) {
        case 'Z':
            return "Zlib";
        case '4':
            return "LZ4";
        case 'F':
            return "FastLZ";
        default:
            return "Unknown(0x" + Integer.toHexString(packType) + ")";
        }
    }

    private boolean isValidPackType(char packType) {
        return packType == 'Z' || packType == '4' || packType == 'F';
    }

    // ========================================================================================================================
    // Parse FST ZWrapper block
    // ========================================================================================================================

    private void parseZWrapperBlock(BinaryParseBuffer reader, long dataSize) throws ParseException, EOFException {
        console.info("--- ZWRAPPER BLOCK ---");
        reader.getLongBE(8, false); // read uncompressed length - ignoring as we use a stream
        dataSize -= 8;
        if (dataSize > 0) {
            try {

                InputStream bis = reader.getStream((int) dataSize);
                GZIPInputStream gzis = new GZIPInputStream(bis);
                // Create new BinaryDecoder for the decompressed data
                BinaryParseBuffer wrappedReader = new BinaryParseBuffer(gzis, DEFAULT_BUFFER_SIZE);
                console.info("  Decompressed zwrapper data, parsing wrapped content");
                // Parse the wrapped content using parsePhase1
                parsePhase1(wrappedReader);
                // Close the wrapped reader
                wrappedReader.close();
            } catch (Exception e) {
                throw new ParseException("Failed to parse ZWrapper block: " + e.getMessage(), e);
            }
        } else {
            console.info("  No wrapper data to process");
        }
    }

    // ========================================================================================================================
    // Compression Support Methods
    // ========================================================================================================================
    /**
     * Unified decompression method that supports all FST compression formats. Handles decompression for various data types in FST files including: -
     * Zlib (Deflate) for frame and geometry data - Gzip for hierarchy data - LZ4 for hierarchy data - LZ4DUO (dual-stage LZ4) for hierarchy data
     *
     * @param compressedData
     *            The compressed data bytes
     * @param compressionType
     *            The compression type (use COMPRESSION_* constants)
     * @param uncompressedSize
     *            Expected size after decompression
     * @param logPrefix
     *            Optional prefix for log messages (for better readability in different contexts)
     * @return Decompressed data or null if decompression fails
     */
    private byte[] decompressData(byte[] compressedData, int compressionType, long uncompressedSize) throws ParseException {
        if (compressedData == null || uncompressedSize <= 0 || uncompressedSize > Integer.MAX_VALUE) {
            throw new ParseException("Invalid compressed data or size: " + uncompressedSize);
        }
        byte[] decompressed = new byte[(int) uncompressedSize];
        try {
            switch (compressionType) {
            case COMPRESSION_NONE:
                if (compressedData.length != uncompressedSize) {
                    throw new ParseException("Uncompressed data size mismatch: expected " + uncompressedSize + " got " + compressedData.length);
                }
                System.arraycopy(compressedData, 0, decompressed, 0, compressedData.length);
                break;
            case COMPRESSION_ZLIB:
                Inflater inflater = new Inflater();
                inflater.setInput(compressedData);
                int resultLength = inflater.inflate(decompressed);
                if (inflater.needsInput() && resultLength < uncompressedSize) {
                    inflater.end();
                    throw new ParseException("Inflater needs more input but no more data available");
                }
                inflater.end();
                if (resultLength != uncompressedSize) {
                    if (resultLength > 0 && resultLength < uncompressedSize) {
                        byte[] partialResult = new byte[resultLength];
                        System.arraycopy(decompressed, 0, partialResult, 0, resultLength);
                        return partialResult;
                    }
                    throw new ParseException("Decompression size mismatch. Expected: " + uncompressedSize + " got: " + resultLength);
                }
                break;
            case COMPRESSION_GZIP:
                try (ByteArrayInputStream bis = new ByteArrayInputStream(compressedData); GZIPInputStream gzis = new GZIPInputStream(bis)) {
                    int totalRead = 0;
                    int bytesRead;
                    while (totalRead < decompressed.length && (bytesRead = gzis.read(decompressed, totalRead, decompressed.length - totalRead)) > 0) {
                        totalRead += bytesRead;
                    }
                    if (totalRead != uncompressedSize) {
                        throw new ParseException("Gzip size mismatch: expected " + uncompressedSize + " got " + totalRead);
                    }
                }
                break;
            case COMPRESSION_LZ4:
                try {
                    IndexedByteArray src = new IndexedByteArray(compressedData, 0);
                    IndexedByteArray dst = new IndexedByteArray(decompressed, 0);
                    LZ4Codec codec = new LZ4Codec();
                    if (!codec.inverse(src, dst)) {
                        throw new ParseException("LZ4 decompression failed");
                    }
                } catch (Exception e) {
                    throw new ParseException("LZ4 decompression error: " + e.getMessage(), e);
                }
                break;
            case COMPRESSION_LZ4DUO:
                try {
                    int intermediateSize = compressedData.length * 4;
                    byte[] intermediate = new byte[intermediateSize];
                    IndexedByteArray src1 = new IndexedByteArray(compressedData, 0);
                    IndexedByteArray dst1 = new IndexedByteArray(intermediate, 0);
                    LZ4Codec codec1 = new LZ4Codec();
                    if (!codec1.inverse(src1, dst1)) {
                        throw new ParseException("LZ4DUO first stage decompression failed");
                    }
                    IndexedByteArray src2 = new IndexedByteArray(intermediate, 0);
                    IndexedByteArray dst2 = new IndexedByteArray(decompressed, 0);
                    LZ4Codec codec2 = new LZ4Codec();
                    if (!codec2.inverse(src2, dst2)) {
                        throw new ParseException("LZ4DUO second stage decompression failed");
                    }
                } catch (Exception e) {
                    throw new ParseException("LZ4DUO decompression error: " + e.getMessage(), e);
                }
                break;
            case COMPRESSION_FASTLZ:
                try {
                    FastLZ.decompress(compressedData, decompressed);
                } catch (Exception e) {
                    throw new ParseException("FastLZ decompression error: " + e.getMessage(), e);
                }
                break;
            default:
                throw new ParseException("Unsupported compression type: " + compressionType);
            }
            return decompressed;
        } catch (java.util.zip.DataFormatException e) {
            throw new ParseException("Data format error during decompression: " + e.getMessage(), e);
        } catch (Exception e) {
            throw new ParseException("Decompression failed: " + e.getMessage(), e);
        }
    }

}
