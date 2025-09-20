package de.toem.impulse.extension.eda.waveform.fst;

import java.io.BufferedInputStream;
import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
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
        BinaryDecoder reader = null;
        try {
            // Set up console logging
            console = new ConfiguredConsoleStream(Ide.DEFAULT_CONSOLE, getLabel(), ConfiguredConsoleStream.logging(getProperties()));
            console.info("FST Reader initialized - parsing file");
            // Wrap input stream in BinaryDecoder for all binary access
            reader = new BinaryDecoder(new DataInputStream(new BufferedInputStream(in)));
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
                if (reader != null) {
                    reader.close();
                } else if (in != null) {
                    in.close();
                }
            } catch (Exception e) {
                // Ignore exceptions on close
            }
        }
    }

    void parsePhase1(BinaryDecoder reader) throws ParseException {
        // Parse FST file blocks sequentially
        int blockCount = 0;
        console.info("=== Starting FST Block Parsing ===");
        try {
            while (true) {
                // Read block type
                int blockType = reader.readUInt8();
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
                    sectionLength = reader.readUInt64();
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
                        reader.readFully(fullBlock, 9, (int) dataSize);
                        console.info("  Read VCDATA block: 1 byte type + 8 bytes length + ", dataSize, " bytes payload = ", fullBlock.length,
                                " total bytes");
                        // Store the complete block for later processing
                        // Time range will be determined in phase 2
                        addDataBlock(fullBlock, 0, 0);
                        console.info("  Stored VCDATA block for phase 2 processing");
                        // Skip to next block
                        continue;
                    } else if(isZwrapperBlock) {
                        // For ZWRAPPER blocks: read entire block including type and length
                        parseZWrapperBlock(reader, dataSize);
                        continue;
                    }else {
                        // For other blocks: read payload only (existing logic)
                        blockData = new byte[(int) dataSize];
                        reader.readFully(blockData);
                        console.info("  Read", blockData.length, "bytes of block data");
                    }
                } catch (Exception e) {
                    throw new ParseException("Failed to read header block: " + e.getMessage(), e);
                }
                // Create a new BinaryDecoder for this block's data
                BinaryDecoder blockReader = new BinaryDecoder(blockData);
                // Dispatch to specific block handler with isolated block data
                try {
                    switch (blockType) {
                    case FST_BL_HDR:
                        parseHeaderBlock(blockReader);
                        break;
                    case FST_BL_BLACKOUT:
                        parseBlackoutBlock(blockReader);
                        break;
                    case FST_BL_GEOM:
                        parseGeometryBlock(blockReader);
                        break;
                    case FST_BL_HIER:
                    case FST_BL_HIER_LZ4:
                    case FST_BL_HIER_LZ4DUO:
                        parseHierarchyBlock(blockReader, blockType);
                        break;
                    case FST_BL_SKIP:
                        parseSkipBlock(blockReader);
                        break;
                    default:
                        console.info("  WARNING: Unknown block type, skipped", blockData.length, "bytes");
                        break;
                    }
                } finally {
                    try {
                        blockReader.close();
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
                BinaryDecoder blockReader = new BinaryDecoder(storedBlock);
                try {
                    // Process the value change block
                    parseValueChangeBlock(blockReader);
                } catch (Exception e) {
                    throw new ParseException("Failed to process VCDATA block " + i + ": " + e.getMessage(), e);
                } finally {
                    try {
                        blockReader.close();
                    } catch (Exception e) {
                        // Ignore close errors
                    }
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
     * @param reader
     *            BinaryDecoder for reading the header block data
     * @throws ParseException
     *             If an error occurs during parsing or if header is parsed twice
     * @throws EOFException
     *             If the end of the file is reached unexpectedly
     */
    private void parseHeaderBlock(BinaryDecoder reader) throws ParseException, EOFException {
        // Check if header has already been parsed
        if (headerParsed) {
            throw new ParseException("Header block has already been parsed - duplicate header detected");
        }
        console.info("--- HEADER BLOCK ---");
        // +8 for section length field itself
        long sectionLength = reader.size() + 8;
        console.info("  Section length:", sectionLength, "bytes (0x", Long.toHexString(sectionLength), ")");
        if (sectionLength != 329) {
            console.info("  WARNING: Expected section length 329, got", sectionLength);
        }
        // Read header fields in order - checking C code for exact layout
        // NOTE: ALL FST multi-byte integers are big-endian per fstReaderUint64() in C implementation
        // +1: start_time (8 bytes)
        this.startTime = reader.readUInt64();
        // +9: end_time (8 bytes)
        this.endTime = reader.readUInt64() + 1;
        // +17: endian test (8 bytes) - ONLY for detecting host endianness
        this.littleEndian = reader.readEndianTestValue();
        // Continue with big-endian reading - FST format is always big-endian
        // +25: mem_used_by_writer (8 bytes)
        this.memoryUsed = reader.readUInt64();
        // +33: scope_count (8 bytes)
        this.numScopes = reader.readUInt64();
        // +41: var_count (8 bytes)
        this.numVars = reader.readUInt64();
        // +49: maxhandle (8 bytes)
        this.maxHandle = reader.readUInt64();
        // +57: vc_section_count (8 bytes)
        this.sectionCount = reader.readUInt64();
        // +65: timescale (1 byte)
        this.timescale = reader.readInt8();
        // The next byte is actually the first character of the version string ('G')
        // Read simulation version string (128 bytes) starting with that 'G'
        this.simVersion = reader.readFixedString(FST_HDR_SIM_VERSION_SIZE);
        // Read date string (119 bytes)
        this.dateString = reader.readFixedString(FST_HDR_DATE_SIZE);
        // Read file type
        // +321
        this.fileType = reader.readUInt8();
        // Read time zero offset (big-endian like other header fields)
        // +322
        this.timezero = reader.readUInt64();

        // Log header information
        console.info("  Header Information:");
        console.info("    Start Time:", startTime);
        console.info("    End Time:", endTime);
        console.info("    Memory Used:", memoryUsed, "bytes");
        console.info("    Number of Scopes:", numScopes);
        console.info("    Number of Variables:", numVars);
        console.info("    Max Handle ID:", maxHandle);
        console.info("    Section Count:", sectionCount);
        // Cast to signed byte
        console.info("    Timescale:", (byte) timescale);
        console.info("    File Type:", fileType, "(", getFileTypeName(fileType), ")");
        console.info("    Time Zero:", timezero);
        console.info("    Simulation Version: '", simVersion, "'");
        console.info("    Date: '", dateString, "'");
        console.info("  Endianness:", this.littleEndian ? "Little-endian" : "Big-endian");
        // Initialize waveform variables array with maxHandle+1 size (handle IDs are 1-based)
        waveformVariables = new FstVariable[(int) (maxHandle + 1)];
        console.info("  Initialized waveform variables array with size:", maxHandle + 1);
        // Mark header as parsed
        headerParsed = true;
        console.info("  Header parsing completed successfully");

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
    private void parseValueChangeBlock(BinaryDecoder reader) throws ParseException, EOFException {
        // Read block type and section length from the beginning of the stored block
        int blockType = reader.readUInt8();
        long sectionLength = reader.readUInt64();
        String blockTypeName = BLOCK_TYPE_NAMES.get(blockType);
        console.info("---", blockTypeName, "BLOCK ---");
        console.info("  Section length:", sectionLength, "bytes");
        // Read value change block header from the block data
        // All integers in FST files are always big-endian per C fstReaderUint64() implementation
        long startTime = reader.readUInt64();
        long endTime = reader.readUInt64();
        long memoryRequired = reader.readUInt64();
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
    private void parseValueChangeDataSections(BinaryDecoder reader, long dataSize) throws ParseException, EOFException {
        console.info("  Parsing value change data sections (", dataSize, "bytes)");
        long bytesProcessed = 0;
        int sectionCount = 0;
        try {
            // Value change data is organized in sections with different types
            // Common sections include: frame data, value change data, chain data, time data
            while (bytesProcessed < dataSize && reader.hasMoreData()) {
                sectionCount++;
                long sectionStartPos = reader.getTotalBytesRead();
                // Read section header - typically starts with a length or type indicator
                int sectionType = reader.readUInt8();
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
                    long sectionLength = reader.readVarint();
                    bytesProcessed += BinaryDecoder.getVarintSize(sectionLength) + sectionLength;
                    reader.skipBytes((int) sectionLength);
                    console.info("    Skipped", sectionLength, "bytes");
                    break;
                }
                // Safety check to prevent infinite loops
                long currentPos = reader.getTotalBytesRead();
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
    private long parseFrameSection(BinaryDecoder reader) throws ParseException, EOFException {
        console.info("      FRAME section");
        long frameLength = reader.readVarint();
        long timeFrame = reader.readVarint();
        console.info("        Frame length:", frameLength);
        console.info("        Time frame:", timeFrame);
        // Frame data contains compressed change information
        if (frameLength > 0) {
            reader.skipBytes((int) frameLength);
            console.info("        Skipped", frameLength, "bytes of frame data");
        }
        return BinaryDecoder.getVarintSize(frameLength) + BinaryDecoder.getVarintSize(timeFrame) + frameLength;
    }

    /**
     * Parse a value change section within value change data
     */
    private long parseValueChangeSection(BinaryDecoder reader) throws ParseException, EOFException {
        console.info("      VALUE_CHANGE section");
        long changeLength = reader.readVarint();
        long numChanges = reader.readVarint();
        console.info("        Change data length:", changeLength);
        console.info("        Number of changes:", numChanges);
        // Value change data contains signal handle and new value pairs
        if (changeLength > 0) {
            reader.skipBytes((int) changeLength);
            console.info("        Skipped", changeLength, "bytes of value change data");
        }
        return BinaryDecoder.getVarintSize(changeLength) + BinaryDecoder.getVarintSize(numChanges) + changeLength;
    }

    /**
     * Parse a chain section within value change data
     */
    private long parseChainSection(BinaryDecoder reader) throws ParseException, EOFException {
        console.info("      CHAIN section");
        long chainLength = reader.readVarint();
        long chainCount = reader.readVarint();
        console.info("        Chain data length:", chainLength);
        console.info("        Chain count:", chainCount);
        // Chain data links together related value changes
        if (chainLength > 0) {
            reader.skipBytes((int) chainLength);
            console.info("        Skipped", chainLength, "bytes of chain data");
        }
        return BinaryDecoder.getVarintSize(chainLength) + BinaryDecoder.getVarintSize(chainCount) + chainLength;
    }

    /**
     * Parse a time section within value change data
     */
    private long parseTimeSection(BinaryDecoder reader) throws ParseException, EOFException {
        console.info("      TIME section");
        long timeLength = reader.readVarint();
        long timeBase = reader.readVarint();
        console.info("        Time data length:", timeLength);
        console.info("        Time base:", timeBase);
        // Time data contains timestamp information for value changes
        if (timeLength > 0) {
            reader.skipBytes((int) timeLength);
            console.info("        Skipped", timeLength, "bytes of time data");
        }
        return BinaryDecoder.getVarintSize(timeLength) + BinaryDecoder.getVarintSize(timeBase) + timeLength;
    }

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
    private void parseValueChangeDynAliasBlock(BinaryDecoder reader, long dataSize, int blockType) throws ParseException, EOFException {
        console.info("  Parsing dynamic alias value change block (", dataSize, "bytes)");
        int startPos = reader.getPosition();
        try {
            // ========================================================================================================================
            // Read frame section header
            // ========================================================================================================================
            long frameUclen = reader.readVarint();
            long frameClen = reader.readVarint();
            long frameMaxHandle = reader.readVarint();
            console.info("      Frame section: uclen=", frameUclen, ", clen=", frameClen, ", maxHandle=", frameMaxHandle);
            // Calculate frame header size
            long frameHeaderSize = BinaryDecoder.getVarintSize(frameUclen) + BinaryDecoder.getVarintSize(frameClen)
                    + BinaryDecoder.getVarintSize(frameMaxHandle);
            // Skip frame data for now - process after layout calculation
            int frameDataPos = reader.getPosition();
            reader.skipBytes((int) frameClen);

            // Read VC section header
            long vcMaxHandle = reader.readVarint();
            int packType = reader.readUInt8();
            char packTypeChar = (char) packType;
            String compressionType = getCompressionTypeName(packTypeChar);
            if (!isValidPackType(packTypeChar))
                throw new ParseException("Invalid pack type");
            // Calculate VC header size and capture actual VC data start position
            // +1 for pack type
            long vcHeaderSize = BinaryDecoder.getVarintSize(vcMaxHandle) + 1;
            // Actual position where VC data starts
            long vcDataStartPos = reader.getPosition();
            // Calculate block layout positions
            // Safety check: ensure we have enough data
            if (reader.size() < 24)
                throw new ParseException("Block too small for time header: " + reader.size() + " bytes");
            console.info("      VC section: maxHandle=", vcMaxHandle, ", packType=", packTypeChar, " (", compressionType, "), headerSize=",
                    vcHeaderSize, ", dataStartPos=", vcDataStartPos);

            // Read time section header from end (last 24 bytes)
            int timeHeaderPos = reader.size() - 24;
            reader.setPosition(timeHeaderPos);
            long tsecUclen = reader.readUInt64();
            long tsecClen = reader.readUInt64();
            long tsecNitems = reader.readUInt64();
            long timeDataPos = timeHeaderPos - tsecClen;
            console.info("      Time section: tsecUclen=", tsecUclen, ", tsecClen=", tsecClen, ", tsecNitems=", tsecNitems);

            // Read chain section header (at block_end - 24 - tsec_clen - 8)
            int chainHeaderPos = reader.size() - 24 - (int) tsecClen - 8;
            reader.setPosition(chainHeaderPos);
            long chainClen = reader.readUInt64();
            long chainDataPos = chainHeaderPos - chainClen;
            console.info("      Chain section: chainClen=", chainClen, ", chainDataPos=", chainDataPos);

            long vcDataSize = dataSize - frameHeaderSize - frameClen - vcHeaderSize - 8 - chainClen - 24 - tsecClen;
            console.info("      VC section: vcDataSize=", vcDataSize);

            // ========================================================================================================================
            // Process frame data
            // ========================================================================================================================

            reader.setPosition(frameDataPos);
            if (frameClen > 0) {
                byte[] compressedFrameData = new byte[(int) frameClen];
                reader.readFully(compressedFrameData);
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
            reader.setPosition(timeDataPos);
            if (tsecClen > 0) {
                byte[] compressedTimeData = new byte[(int) tsecClen];
                reader.readFully(compressedTimeData);
                byte[] timeData = decompressData(compressedTimeData, COMPRESSION_ZLIB, tsecUclen);
                if (timeData != null) {
                    BinaryDecoder timeReader = new BinaryDecoder(timeData);
                    // Parse time section data to extract absolute timestamps
                    timestamps = new long[(int) tsecNitems];
                    long currentTime = timezero;
                    for (int i = 0; i < tsecNitems && timeReader.hasMoreData(); i++) {
                        // Read time delta as varint
                        long timeDelta = timeReader.readVarint();
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
                    timeReader.close();
                } else {
                    console.info("      Failed to decompress time data");
                }
            }

            // ========================================================================================================================
            // Parse chain section to set offset/length directly in FstVariables
            // ========================================================================================================================

            console.info("      Parsing chain section (", chainClen, "compressed bytes)");
            if (chainDataPos < 0 || chainDataPos + chainClen > reader.size()) {
                console.info("      Chain data position invalid - skipping chain parsing");
            } else {
                // Read chain data
                reader.setPosition(chainDataPos);
                console.info("      Reading", chainClen, "bytes of chain data from position", chainDataPos);
                byte[] chainData = new byte[(int) chainClen];
                reader.readFully(chainData);
                console.info("      Read", chainData.length, "bytes of chain data");
                // Log first few bytes of compressed data
                StringBuilder hexLog = new StringBuilder();
                for (int i = 0; i < Math.min(chainData.length, 16); i++) {
                    hexLog.append(String.format("%02X ", chainData[i] & 0xFF));
                }
                console.info("      First", Math.min(chainData.length, 16), "bytes of chain data:", hexLog.toString());
                BinaryDecoder chainReader = new BinaryDecoder(chainData);
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
                        long val = chainReader.readSVarint();
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
                        long val = chainReader.readVarint();
                        if (val == 0) {
                            // Alias pair: val==0 followed by target handle
                            long aliasTarget = chainReader.readVarint();
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
                chainReader.close();
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
                reader.setPosition(vcDataStartPos);
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
                                reader.setPosition(signalDataPos);
                                // Read uncompressed length as varint (first part of the chunk)
                                long uncompressedLength = reader.readVarint();
                                // Calculate remaining compressed data size
                                int compressedDataSize = var.chunkLength - reader.getVarintSize(uncompressedLength);
                                // Handle compressed vs uncompressed data
                                byte[] decompressedChunk;
                                if (uncompressedLength == 0) {
                                    // No compression - read remaining data directly
                                    decompressedChunk = new byte[compressedDataSize];
                                    reader.readFully(decompressedChunk);
                                    console.info("          Read", decompressedChunk.length, "bytes of uncompressed data");
                                } else {
                                    // Compressed data - read and decompress
                                    byte[] compressedData = new byte[compressedDataSize];
                                    reader.readFully(compressedData);
                                    console.info("          Read", compressedData.length, "bytes of compressed data, decompressing to",
                                            uncompressedLength);
                                    decompressedChunk = decompressData(compressedData, signalCompressionType, uncompressedLength);
                                    if (decompressedChunk == null)
                                        throw new ParseException("Failed to decompress signal data");
                                }
                                // Parse individual value changes from decompressed data
                                BinaryDecoder chunkReader = new BinaryDecoder(decompressedChunk);
                                int valueChangesInThisSignal = 0;
                                int timeIndex = 0;
                                while (chunkReader.hasMoreData()) {
                                    try {
                                        // Read the time/format varint
                                        long vli = chunkReader.readVarint();
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
                                            long valueLength = chunkReader.readVarint();
                                            // Read value bytes directly from array
                                            int currentPos = chunkReader.getPosition();
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
                                            int currentPos = chunkReader.getPosition();
                                            var.writeChange(timestamps[timeIndex], bitdata, decompressedChunk, currentPos, (int) valueLength);
                                            // Skip past the value bytes
                                            chunkReader.skipBytes((int) valueLength);
                                            valueChangesInThisSignal++;
                                        } else if (var.dataType == ISample.DATA_TYPE_FLOAT) {
                                            int currentPos = chunkReader.getPosition();
                                            timeIndex += vli >> 1;
                                            var.writeChange(timestamps[timeIndex], false, decompressedChunk, currentPos, 8);
                                            // Skip past the value bytes
                                            chunkReader.skipBytes(8);
                                        }
                                    } catch (Exception e) {
                                        throw new ParseException("Error parsing value change", e);
                                    }
                                }
                                chunkReader.close();
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

    private int calculateHeaderSize(long frameUclen, long frameClen, long frameMaxHandle, long vcMaxHandle) {
        return // +1 for pack type
        BinaryDecoder.getVarintSize(frameUclen) + BinaryDecoder.getVarintSize(frameClen) + BinaryDecoder.getVarintSize(frameMaxHandle)
                + BinaryDecoder.getVarintSize(vcMaxHandle) + 1;
    }

    private long estimateVcDataSize(long totalSize, int headerSize, long frameClen) {
        // Estimate: 70% of remaining space after header and frame data
        long remaining = totalSize - headerSize - frameClen;
        return Math.max(0, (long) (remaining * 0.7));
    }

    private void logAliasFormatInfo(int blockType, long vcMaxHandle) {
        if (blockType == FST_BL_VCDATA_DYN_ALIAS2) {
            console.info("    Enhanced format (type 2): signed varint encoding, bit flags for alias types");
        } else {
            console.info("    Standard format: regular varint encoding, value patterns for aliases");
        }
        console.info("    Max signal aliases:", vcMaxHandle);
    }

    // ========================================================================================================================
    // Parse FST blackout block
    // ========================================================================================================================
    private void parseBlackoutBlock(BinaryDecoder reader) throws ParseException, EOFException {
        console.info("--- BLACKOUT BLOCK ---");
        // +8 for section length field itself
        long sectionLength = reader.size() + 8;
        console.info("  Section length:", sectionLength, "bytes");
        try {
            // Read number of blackouts (varint)
            long numBlackouts = reader.readVarint();
            console.info("  Number of blackouts:", numBlackouts);
            // Track bytes read so far: varint size
            long bytesRead = BinaryDecoder.getVarintSize(numBlackouts);
            if (// Sanity check
            numBlackouts > 0 && numBlackouts < 10000)
                console.info("  Blackout entries:");
            long currentTime = 0;
            // Read first few blackout entries for logging (but not all to avoid spam)
            long entriesToLog = Math.min(numBlackouts, 5);
            for (int i = 0; i < entriesToLog; i++) {
                // Activity flag
                int activity = reader.readUInt8();
                long timeDelta = reader.readVarint();
                currentTime += timeDelta;
                bytesRead += 1 + BinaryDecoder.getVarintSize(timeDelta);
                console.info("    Entry", i + 1, ": activity=", (activity != 0 ? "ON" : "OFF"), ", time=", currentTime);
            }
            if (numBlackouts > entriesToLog) {
                console.info("    ... and", numBlackouts - entriesToLog, "more entries");
            }
            // Skip remaining blackout data
            // -8 for section length already excluded
            long remainingBytes = (sectionLength - 8) - bytesRead;
            if (remainingBytes > 0) {
                reader.skipBytes((int) remainingBytes);
                console.info("  Skipped", remainingBytes, "bytes of remaining blackout data");
            }
        } catch (Exception e) {
            // If parsing fails, log the error
            console.info("  Failed to parse blackout data:", e.getMessage());
        }
    }

    // ========================================================================================================================
    // Parse FST geometry block
    // ========================================================================================================================
    /**
     * Parse geometry block header and decompress data if compressed
     */
    private void parseGeometryBlock(BinaryDecoder reader) throws ParseException, EOFException {
        console.info("--- GEOMETRY BLOCK ---");
        // +8 for section length field itself
        long sectionLength = reader.size() + 8;
        console.info("  Section length:", sectionLength, "bytes");
        // Read geometry block header from the block data
        // All FST integers are big-endian
        long uncompressedLength = reader.readUInt64();
        long maxHandle = reader.readUInt64();
        console.info("  Uncompressed length:", uncompressedLength, "bytes");
        console.info("  Max handle:", maxHandle);
        // Calculate compression info
        // 16 = 8 (uclen) + 8 (maxhandle), section length was already excluded
        // -8 for section length, -16 for header
        long compressedDataLength = (sectionLength - 8) - 16;
        boolean isCompressed = compressedDataLength != uncompressedLength;
        if (isCompressed) {
            double compressionRatio = 100.0 * compressedDataLength / uncompressedLength;
            console.info("  Compression ratio:", Math.round(compressionRatio * 100.0) / 100.0, "%");
            console.info("  Compression: Yes (Zlib)");
            if (compressedDataLength > 0) {
                // Read the compressed data
                byte[] compressedData = new byte[(int) compressedDataLength];
                reader.readFully(compressedData);
                // Decompress the data using Zlib
                byte[] decompressedData = decompressData(compressedData, COMPRESSION_ZLIB, uncompressedLength);
                if (decompressedData != null) {
                    console.info("  Successfully decompressed geometry data");
                    // Create a new BinaryDecoder with the decompressed data for further parsing
                    BinaryDecoder geometryReader = new BinaryDecoder(decompressedData);
                    // Parse the decompressed geometry data
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
                // Read the uncompressed data directly
                byte[] geometryData = new byte[(int) compressedDataLength];
                reader.readFully(geometryData);
                // Create a new BinaryDecoder with the data for parsing
                BinaryDecoder geometryReader = new BinaryDecoder(geometryData);
                // Parse the geometry data
                parseGeometryData(geometryReader, compressedDataLength, maxHandle);
                console.info("  Completed geometry data parsing");
            } else {
                console.info("  No geometry data to process");
            }
        }
    }

    /**
     * Parse decompressed geometry data. The geometry block contains signal handle assignments and signal metadata. This is where signals from the
     * hierarchy get their actual handle numbers assigned.
     *
     * @param reader
     *            BinaryDecoder for the decompressed geometry data
     * @param dataLength
     *            Length of the decompressed data
     * @param maxHandle
     *            Maximum handle ID from the geometry block header
     */
    private void parseGeometryData(BinaryDecoder reader, long dataLength, long maxHandle) throws ParseException {
        console.info("  Parsing geometry data (", dataLength, "bytes, max handle:", maxHandle, ")");
        console.info("  Starting from geometry handle:", currentGeometryHandle + 1);
        try {
            int signalsProcessed = 0;
            int signalsWithData = 0;
            int realSignals = 0;
            // Parse signal geometry data continuing from current position
            // Handle IDs are 1-based, so we start from currentGeometryHandle + 1
            long startHandle = currentGeometryHandle + 1;
            long endHandle = currentGeometryHandle + maxHandle;
            for (long handle = startHandle; handle <= endHandle && reader.hasMoreData(); handle++) {
                // Read varint that indicates signal length or type
                long val = reader.readVarint();
                signalsProcessed++;
                // Check if FstVariable already exists for this handle (due to different block ordering)
                FstVariable fstVar = waveformVariables[(int) handle];
                if (fstVar == null) {
                    // Create new FstVariable for this handle
                    fstVar = new FstVariable();
                    fstVar.handle = (int) handle;
                    // Store the FstVariable in the array at the handle index
                    waveformVariables[(int) handle] = fstVar;
                }
                if (val != 0) {
                    // Non-zero value indicates a logic signal with bit width
                    signalsWithData++;
                    if (val != 0xFFFFFFFFL) {
                        // Normal logic signal - val is the bit width
                        fstVar.scale = (int) val;
                        fstVar.dataType = ISample.DATA_TYPE_LOGIC;
                    } else {
                        // Special case: 0xFFFFFFFF indicates zero-length signal
                        fstVar.scale = 0;
                        // Logic type for zero-length
                        fstVar.dataType = ISample.DATA_TYPE_LOGIC;
                    }
                    console.info("    Handle", handle, ": Logic signal,", fstVar.scale, "bits ");
                } else {
                    // Zero value indicates a real (floating-point) signal
                    realSignals++;
                    fstVar.dataType = ISample.DATA_TYPE_FLOAT;
                    // fstVar.scale = 8;
                    console.info("    Handle", handle, ": Real signal, 64-bit float");
                }
                // Report progress periodically
                if (signalsProcessed % 100 == 0) {
                    console.info("    ... processed", signalsProcessed, "of", maxHandle, "signals");
                }
            }
            // Update current geometry handle position for next geometry block
            currentGeometryHandle = endHandle;
            console.info("  Geometry parsing completed:");
            console.info("    Total signals processed:", signalsProcessed);
            console.info("    Logic signals (with data):", signalsWithData);
            console.info("    Real signals (floating-point):", realSignals);
            console.info("    Signals without data:", signalsProcessed - signalsWithData - realSignals);
            console.info("    Current geometry handle position:", currentGeometryHandle);
            // Verify we processed the expected number of handles
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
    private void parseHierarchyBlock(BinaryDecoder reader, int blockType) throws ParseException, EOFException {
        String blockTypeName = BLOCK_TYPE_NAMES.get(blockType);
        console.info("---", blockTypeName, "BLOCK ---");
        // +8 for section length field itself
        long sectionLength = reader.size() + 8;
        console.info("  Section length:", sectionLength, "bytes");
        // Read hierarchy block header from the block data
        // All FST integers are big-endian
        long uncompressedLength = reader.readUInt64();
        console.info("  Uncompressed length:", uncompressedLength, "bytes");
        // -8 for section length, -8 for uncompressed length
        double compressionRatio = 100.0 * ((sectionLength - 8) - 8) / uncompressedLength;
        console.info("  Compression ratio:", Math.round(compressionRatio * 100.0) / 100.0, "%");
        String compressionType;
        switch (blockType) {
        case FST_BL_HIER:
            compressionType = "Gzip";
            break;
        case FST_BL_HIER_LZ4:
            compressionType = "LZ4";
            break;
        case FST_BL_HIER_LZ4DUO:
            compressionType = "LZ4 Dual-stage";
            break;
        default:
            compressionType = "Unknown";
            break;
        }
        console.info("  Compression type:", compressionType);
        // Calculate compressed data size
        // -8 for uncompressed length field
        long compressedDataLength = (sectionLength - 8) - 8;
        if (compressedDataLength > 0) {
            // Read the compressed data
            byte[] compressedData = new byte[(int) compressedDataLength];
            reader.readFully(compressedData);
            // Map block type to compression type
            int actualCompressionType;
            switch (blockType) {
            case FST_BL_HIER:
                actualCompressionType = COMPRESSION_GZIP;
                break;
            case FST_BL_HIER_LZ4:
                actualCompressionType = COMPRESSION_LZ4;
                break;
            case FST_BL_HIER_LZ4DUO:
                actualCompressionType = COMPRESSION_LZ4DUO;
                break;
            default:
                actualCompressionType = COMPRESSION_GZIP;
                break;
            }
            // Decompress the data with the mapped compression type
            byte[] decompressedData = decompressData(compressedData, actualCompressionType, uncompressedLength);
            if (decompressedData != null) {
                console.info("  Successfully decompressed hierarchy data");
                // Create a new BinaryDecoder with the decompressed data for further parsing
                BinaryDecoder hierarchyReader = new BinaryDecoder(decompressedData);
                // Parse the decompressed hierarchy data
                parseHierarchyData(hierarchyReader, uncompressedLength);
                console.info("  Completed hierarchy data parsing");
            } else {
                throw new ParseException("Failed to decompress hierarchy data");
            }
        } else {
            console.info("  No compressed data to process");
        }
    }

    private void parseHierarchyData(BinaryDecoder reader, long dataLength) throws ParseException {
        console.info("  Parsing hierarchy data (", dataLength, "bytes)");
        ICell scope = this.base;
        int entryCount = 0;
        try {
            while (reader.hasMoreData()) {
                int tag = reader.readUInt8();
                entryCount++;
                // FST hierarchy uses different tag values than the FST_HT_* constants
                // The actual format uses FST_ST_* and FST_VT_* values as tags
                switch (tag) {
                case // FST_ST_VCD_SCOPE
                        254:
                    scope = parseHierarchyScope(reader, scope, tag);
                    break;
                case // FST_ST_VCD_UPSCOPE
                        255:
                    if (scope != null) {
                        scope = scope.getCellContainer();
                    }
                    console.info("End scope");
                    break;
                case // FST_ST_GEN_ATTRBEGIN
                        252:
                    parseHierarchyAttributeBegin(reader, tag);
                    break;
                case // FST_ST_GEN_ATTREND
                        253:
                    console.info("End attribute");
                    break;
                default:
                    // Check if this is a variable type (FST_VT_* values 0-29)
                    if (tag >= 0 && tag <= 29) {
                        parseHierarchyVariable(reader, scope, tag);
                    } else {
                        console.info("    UNKNOWN tag:", tag, "(0x", Integer.toHexString(tag), ")");
                        // Try to read and skip the next byte to attempt recovery
                        if (reader.hasMoreData()) {
                            int nextByte = reader.readUInt8();
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
     * Parse a scope entry from hierarchy data (FST_ST_VCD_SCOPE = 254)
     */
    private IRecord.Scope parseHierarchyScope(BinaryDecoder reader, ICell currentScope, int tag) throws ParseException, EOFException {
        int scopeType = reader.readUInt8();
        String scopeName = reader.readNullTerminatedString();
        String scopeComponent = reader.readNullTerminatedString();
        String scopeTypeName = getScopeTypeName(scopeType);
        IRecord.Scope scope = this.addScope(currentScope, scopeName);
        console.info("  Scope: type=", scopeTypeName, ", name='", scopeName, "', component='", scopeComponent, "'");
        return scope;
    }

    /**
     * Parse a variable entry from hierarchy data (FST_VT_* values 0-29)
     */
    private void parseHierarchyVariable(BinaryDecoder reader, ICell currentScope, int varType) throws ParseException, EOFException {
        int varDirection = reader.readUInt8();
        String varName = reader.readNullTerminatedString();
        long varLength = reader.readVarint();
        long varHandle = reader.readVarint();
        String varTypeName = getVariableTypeName(varType);
        String varDirectionName = getVariableDirectionName(varDirection);
        console.info("  Var: type=", varTypeName, varType, " direction=", varDirectionName, varDirection, "length=", varLength, " bits, name='",
                varName, "', handle=", varHandle);
        // Determine the actual handle to use
        long actualHandle;
        if (varHandle == 0) {
            // New variable - assign next sequential handle
            currentHierarchyHandle++;
            actualHandle = currentHierarchyHandle;
            console.info("    ", "  Assigned handle: ", actualHandle, " (NEW)");
        } else {
            // Alias to existing handle - use the existing handle
            actualHandle = varHandle;
            console.info("    ", "  Alias to handle: ", varHandle, " (EXISTING)");
        }
        // Check if handle is within bounds of waveformVariables array
        if (actualHandle <= 0 || actualHandle >= waveformVariables.length) {
            throw new ParseException("Handle " + actualHandle + " is out of bounds (array size: " + waveformVariables.length + ")");
        }
        // Get or create FstVariable for this handle
        FstVariable fstVar = waveformVariables[(int) actualHandle];
        if (fstVar == null) {
            // Create new FstVariable for this handle
            fstVar = new FstVariable();
            waveformVariables[(int) actualHandle] = fstVar;
            console.info("    ", "  Created new FstVariable for handle: ", actualHandle);
        } else {
            console.info("    ", "  Using existing FstVariable for handle: ", actualHandle);
        }

        varName = varName.replaceAll("\\s+\\[", "[");
        int vec0Idx = varName.lastIndexOf('[');
        if (vec0Idx > 0) {
            fstVar.idxname = varName.substring(0, vec0Idx).trim();
            int dimIdx = varName.indexOf(':', vec0Idx);
            int vec1Idx = varName.indexOf(']', vec0Idx);
            if (vec1Idx > 0) {
                if (dimIdx > 0) {
                    fstVar.idx0 = Utils.parseInt(varName.substring(vec0Idx + 1, dimIdx).trim(), -1); // High index
                    fstVar.idx1 = Utils.parseInt(varName.substring(dimIdx + 1, vec1Idx).trim(), -1); // Low index
                } else
                    fstVar.idx0 = Utils.parseInt(varName.substring(vec0Idx + 1, vec1Idx).trim(), -1); // High index
            }
            // Ensure idx0 >= idx1 for bit ranges
            if (fstVar.idx1 > fstVar.idx0) {
                int swap = fstVar.idx0;
                fstVar.idx0 = fstVar.idx1;
                fstVar.idx1 = swap;
            }
        }

        // Populate FstVariable fields
        fstVar.handle = (int) actualHandle;
        fstVar.name = varName;
        fstVar.description = varTypeName;// + " " + varDirectionName + " (" + varLength + " bits)";
        fstVar.tags = null;// varTypeName + "," + varDirectionName;
        fstVar.scope = currentScope;
    }

    /**
     * Parse an attribute begin entry from hierarchy data (FST_ST_GEN_ATTRBEGIN = 252)
     */
    private void parseHierarchyAttributeBegin(BinaryDecoder reader, int tag) throws ParseException, EOFException {
        int attrType = reader.readUInt8();
        int subType = reader.readUInt8();
        String attrName = reader.readNullTerminatedString();
        long attrArg = reader.readVarint();
        console.info("ATTR_BEGIN: ", attrName, " (tag=", tag, ", type=", attrType, ", subType=", subType, ", arg=", attrArg, ")");
        console.warning("Attributes are not supported and will be ignored.");
    }

    /**
     * Generate indentation string for hierarchy depth visualization
     */
    private String getIndent(int depth) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < depth; i++) {
            sb.append("  ");
        }
        return sb.toString();
    }

    /**
     * Get scope type name from numeric value
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
     * Get variable type name from numeric value (FST_VT_* constants)
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
     * Get variable direction name from numeric value (FST_VD_* constants)
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
    private void parseSkipBlock(BinaryDecoder reader) throws ParseException, EOFException {
        console.info("--- SKIP BLOCK ---");
        // +8 for section length field itself
        long sectionLength = reader.size() + 8;
        console.info("  Section length:", sectionLength, "bytes");
        // Skip block data (all remaining data in this block should be skipped)
        // -8 for section length already excluded
        long dataSize = sectionLength - 8;
        if (dataSize > 0) {
            reader.skipBytes((int) dataSize);
            console.info("  Skipped", dataSize, "bytes of skip block data");
        }
    }

    // ========================================================================================================================
    // Parse FST ZWrapper block
    // ========================================================================================================================

    private static class BinaryDecoderInputStream extends InputStream {
        private final BinaryDecoder decoder;
        private final long size;
        private long bytesRead = 0;

        /**
         * Constructor for BinaryDecoderInputStream.
         *
         * @param decoder The BinaryDecoder to read from
         * @param size    The maximum number of bytes to read
         */
        public BinaryDecoderInputStream(BinaryDecoder decoder, long size) {
            this.decoder = decoder;
            this.size = size;
        }

        @Override
        public int read() throws IOException {
            if (bytesRead >= size) {
                return -1;
            }
            try {
                int b = decoder.readUInt8();
                bytesRead++;
                return b;
            } catch (EOFException e) {
                return -1;
            } catch (ParseException e) {
                throw new IOException(e);
            }
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            if (bytesRead >= size) {
                return -1;
            }
            long remaining = size - bytesRead;
            int toRead = (int) Math.min(len, remaining);
            if (toRead == 0) {
                return -1;
            }
            try {
                decoder.readFully(b, off, toRead);
                bytesRead += toRead;
                return toRead;
            } catch (EOFException e) {
                return -1;
            } catch (ParseException e) {
                throw new IOException(e);
            }
        }
    }
    private void parseZWrapperBlock(BinaryDecoder reader,long dataSize) throws ParseException, EOFException {
        console.info("--- ZWRAPPER BLOCK ---");
        reader.readUInt64(); // read uncompressed length - ignoring as we use a stream
        dataSize-=8;
        if (dataSize > 0) {
            try {

                BinaryDecoderInputStream bis = new BinaryDecoderInputStream(reader, dataSize);
                GZIPInputStream gzis = new GZIPInputStream(bis);
                DataInputStream dis = new DataInputStream(gzis);
                // Create new BinaryDecoder for the decompressed data
                BinaryDecoder wrappedReader = new BinaryDecoder(dis);
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

    // No legacy methods needed - using unified decompressData method directly
    // ========================================================================================================================
    // FST BinaryDecoder
    // ========================================================================================================================
    /**
     * Binary decoder for FST files that handles all binary data reading operations.
     *
     * This class provides FST-specific reading methods including endianness detection, varint decoding, and various integer formats. It uses an
     * internal byte buffer for efficient binary reading, supporting both direct byte array access and input stream reading with automatic buffering.
     * All FST multi-byte integers are big-endian except for the endian test value.
     */
    private static class BinaryDecoder {

        // 16KB buffer
        private static final int DEFAULT_BUFFER_SIZE = 16 * 1024;

        // Original input stream (if used)
        private final InputStream inputStream;

        // Internal byte buffer
        private final byte[] buffer;

        // Current position in buffer
        private int bufferPos;

        // Number of valid bytes in buffer
        private int bufferLimit;

        // True if using input stream
        private boolean isStreamBased;

        // Track total bytes read for debugging
        private long totalBytesRead = 0;

        /**
         * Constructor that wraps a DataInputStream for FST binary reading Creates an internal 16KB buffer and reads from the stream as needed.
         *
         * @param dis
         *            The DataInputStream to wrap
         */
        public BinaryDecoder(DataInputStream dis) {
            this.inputStream = dis;
            this.buffer = new byte[DEFAULT_BUFFER_SIZE];
            this.bufferPos = 0;
            this.bufferLimit = 0;
            this.isStreamBased = true;
        }

        /**
         * Constructor that uses a pre-existing byte array for binary reading
         *
         * @param data
         *            The byte array containing FST data
         */
        public BinaryDecoder(byte[] data) {
            this.inputStream = null;
            this.buffer = data;
            this.bufferPos = 0;
            this.bufferLimit = data.length;
            this.isStreamBased = false;
        }

        /**
         * Constructor that uses a byte array with specified offset and length
         *
         * @param data
         *            The byte array containing FST data
         * @param offset
         *            Starting offset in the array
         * @param length
         *            Number of bytes to use from the array
         */
        public BinaryDecoder(byte[] data, int offset, int length) {
            this.inputStream = null;
            this.buffer = new byte[length];
            System.arraycopy(data, offset, this.buffer, 0, length);
            this.bufferPos = 0;
            this.bufferLimit = length;
            this.isStreamBased = false;
        }

        // ========================================================================================================================
        // Buffer Management
        // ========================================================================================================================
        /**
         * Ensure that at least the specified number of bytes are available in the buffer. If using stream-based reading, this will refill the buffer
         * as needed.
         *
         * @param bytesNeeded
         *            Number of bytes that must be available
         * @throws ParseException
         *             If not enough data is available or read error occurs
         */
        private void ensureAvailable(int bytesNeeded) throws ParseException, EOFException {
            if (bufferPos + bytesNeeded <= bufferLimit) {
                // Already have enough data
                return;
            }
            if (!isStreamBased) {
                throw new EOFException(
                        "Not enough data in buffer. Need " + bytesNeeded + " bytes, but only " + (bufferLimit - bufferPos) + " available");
            }
            // Stream-based reading: need to refill buffer
            refillBuffer(bytesNeeded);
        }

        /**
         * Refill the buffer from the input stream, ensuring at least the specified bytes are available.
         *
         * @param bytesNeeded
         *            Minimum number of bytes needed
         * @throws ParseException
         *             If read error occurs or not enough data available
         */
        private void refillBuffer(int bytesNeeded) throws ParseException, EOFException {
            if (bytesNeeded > buffer.length) {
                throw new ParseException("Requested " + bytesNeeded + " bytes exceeds buffer size " + buffer.length);
            }
            // Move any remaining data to the beginning of the buffer
            int remainingBytes = bufferLimit - bufferPos;
            if (remainingBytes > 0) {
                System.arraycopy(buffer, bufferPos, buffer, 0, remainingBytes);
            }
            // Reset buffer position and limit
            bufferPos = 0;
            bufferLimit = remainingBytes;
            // Read more data from stream
            while (bufferLimit < bytesNeeded) {
                int spaceAvailable = buffer.length - bufferLimit;
                int bytesRead;
                try {
                    bytesRead = inputStream.read(buffer, bufferLimit, spaceAvailable);
                } catch (java.io.IOException e) {
                    throw new ParseException("IO error while reading from stream: " + e.getMessage(), e);
                }
                if (bytesRead == -1) {
                    throw new EOFException("End of stream reached. Need " + bytesNeeded + " bytes, but only " + bufferLimit + " available");
                }
                bufferLimit += bytesRead;
            }
        }

        /**
         * Get total bytes read so far (for debugging/monitoring)
         *
         * @return Total bytes read
         */
        public long getTotalBytesRead() {
            return totalBytesRead;
        }

        /**
         * Get the current position in the buffer. This returns the current read position within the internal buffer.
         *
         * @return Current buffer position
         */
        public int getPosition() {
            return bufferPos;
        }

        /**
         * Set the current position in the buffer. This is only allowed for array-based decoders (not stream-based). Setting position allows seeking
         * within the buffer for random access.
         *
         * @param pos
         *            The new position to set (must be within buffer bounds)
         * @throws IllegalStateException
         *             If called on a stream-based decoder
         * @throws IllegalArgumentException
         *             If position is out of bounds
         */
        public void setPosition(long pos) {
            if (isStreamBased) {
                throw new IllegalStateException("Cannot set position on stream-based decoder");
            }
            if (pos < 0 || pos > bufferLimit) {
                throw new IllegalArgumentException("Position " + pos + " is out of bounds [0, " + bufferLimit + "]");
            }
            bufferPos = (int) pos;
        }

        /**
         * Get the total size of the buffer (only supported for array-based decoders). This returns the total length of the internal buffer for
         * array-based decoders.
         *
         * @return Total size of the buffer in bytes
         * @throws IllegalStateException
         *             If called on a stream-based decoder
         */
        public int size() {
            if (isStreamBased) {
                throw new IllegalStateException("size() method is only supported for array-based decoders");
            }
            return bufferLimit;
        }

        // ========================================================================================================================
        // Endianness Detection
        // ========================================================================================================================
        /**
         * Read double and perform endianness test to detect host machine byte order. This is used only for detecting the host machine's endianness,
         * not for file data. All FST file data is always big-endian regardless of this test result.
         *
         * @return The endian test value (should be FST_DOUBLE_ENDTEST)
         * @throws ParseException
         *             If the endian test fails or read error occurs
         */
        public boolean readEndianTestValue() throws ParseException, EOFException {
            ensureAvailable(8);
            // Try interpreting as little-endian first
            long bitsLE = 0;
            for (int i = 0; i < 8; i++) {
                bitsLE |= ((long) (buffer[bufferPos + i] & 0xFF)) << (i * 8);
            }
            double valueLE = Double.longBitsToDouble(bitsLE);
            if (Math.abs(valueLE - FST_DOUBLE_ENDTEST) < 1e-10) {
                bufferPos += 8;
                totalBytesRead += 8;
                return true;
            }
            // Try interpreting as big-endian
            long bitsBE = 0;
            for (int i = 0; i < 8; i++) {
                bitsBE = (bitsBE << 8) | (buffer[bufferPos + i] & 0xFF);
            }
            double valueBE = Double.longBitsToDouble(bitsBE);
            if (Math.abs(valueBE - FST_DOUBLE_ENDTEST) < 1e-10) {
                bufferPos += 8;
                totalBytesRead += 8;
                return false;
            }
            throw new ParseException("Endian test failed. Neither little-endian (" + valueLE + ") nor big-endian (" + valueBE
                    + ") matches expected value " + FST_DOUBLE_ENDTEST);
        }

        // ========================================================================================================================
        // Basic Data Types (FST uses big-endian for all multi-byte integers)
        // ========================================================================================================================
        /**
         * Read unsigned byte (0-255)
         *
         * @return Unsigned byte value
         * @throws ParseException
         *             If read error occurs
         */
        public int readUInt8() throws ParseException, EOFException {
            ensureAvailable(1);
            int value = buffer[bufferPos] & 0xFF;
            bufferPos++;
            totalBytesRead++;
            return value;
        }

        /**
         * Read signed byte (-128 to 127)
         *
         * @return Signed byte value
         * @throws ParseException
         *             If read error occurs
         */
        public byte readInt8() throws ParseException, EOFException {
            ensureAvailable(1);
            byte value = buffer[bufferPos];
            bufferPos++;
            totalBytesRead++;
            return value;
        }

        /**
         * Read big-endian 64-bit unsigned integer. All FST multi-byte integers are big-endian per the reference implementation.
         *
         * @return 64-bit unsigned integer value
         * @throws ParseException
         *             If read error occurs
         */
        public long readUInt64() throws ParseException, EOFException {
            ensureAvailable(8);
            long value = 0;
            for (int i = 0; i < 8; i++) {
                value = (value << 8) | (buffer[bufferPos + i] & 0xFF);
            }
            bufferPos += 8;
            totalBytesRead += 8;
            return value;
        }

        /**
         * Read big-endian 32-bit unsigned integer. All FST multi-byte integers are big-endian per the reference implementation.
         *
         * @return 32-bit unsigned integer value
         * @throws ParseException
         *             If read error occurs
         */
        public long readUInt32() throws ParseException, EOFException {
            ensureAvailable(4);
            long value = 0;
            for (int i = 0; i < 4; i++) {
                value = (value << 8) | (buffer[bufferPos + i] & 0xFF);
            }
            bufferPos += 4;
            totalBytesRead += 4;
            return value;
        }

        /**
         * Read big-endian 16-bit unsigned integer. All FST multi-byte integers are big-endian per the reference implementation.
         *
         * @return 16-bit unsigned integer value
         * @throws ParseException
         *             If read error occurs
         */
        public int readUInt16() throws ParseException, EOFException {
            ensureAvailable(2);
            int value = ((buffer[bufferPos] & 0xFF) << 8) | (buffer[bufferPos + 1] & 0xFF);
            bufferPos += 2;
            totalBytesRead += 2;
            return value;
        }

        // ========================================================================================================================
        // FST-Specific Data Types
        // ========================================================================================================================
        /**
         * Read variable-length integer (varint) as used in FST format. Varints are little-endian encoded with continuation bits.
         *
         * @return Variable-length integer value
         * @throws ParseException
         *             If read error occurs
         */
        public long readVarint() throws ParseException, EOFException {
            long result = 0;
            int shift = 0;
            byte b;
            do {
                ensureAvailable(1);
                b = buffer[bufferPos];
                bufferPos++;
                totalBytesRead++;
                result |= ((long) (b & 0x7F)) << shift;
                shift += 7;
            } while ((b & 0x80) != 0);
            return result;
        }

        /**
         * Read signed variable-length integer (svarint) as used in FST format.
         *
         * @return Signed variable-length integer value
         * @throws ParseException
         *             If read error occurs
         */
        public long readSVarint() throws ParseException, EOFException {
            long rc = 0;
            final long one = 1L;
            final int siz = Long.SIZE;
            int shift = 0;
            byte byt;
            int startPos = bufferPos;
            do {
                ensureAvailable(1);
                byt = buffer[bufferPos];
                bufferPos++;
                totalBytesRead++;
                rc |= ((long) (byt & 0x7F)) << shift;
                shift += 7;
            } while ((byt & 0x80) != 0);
            if ((shift < siz) && ((byt & 0x40) != 0)) {
                // sign extend
                rc |= -(one << shift);
            }
            // skiplen = bufferPos - startPos; // Not used here, but matches C API
            return rc;
        }

        /**
         * Calculate the number of bytes a varint value would occupy when encoded
         *
         * @param value
         *            The value to calculate encoding size for
         * @return Number of bytes required for varint encoding
         */
        public static int getVarintSize(long value) {
            int size = 1;
            while (value >= 0x80) {
                value >>>= 7;
                size++;
            }
            return size;
        }

        /**
         * Read null-terminated string of specified maximum length. Used for FST header strings (simulation version, date).
         *
         * @param maxLength
         *            Maximum length to read
         * @return String with null termination and whitespace trimmed
         * @throws ParseException
         *             If read error occurs
         */
        public String readFixedString(int maxLength) throws ParseException, EOFException {
            ensureAvailable(maxLength);
            // Find null terminator
            int nullIndex = maxLength;
            for (int i = 0; i < maxLength; i++) {
                if (buffer[bufferPos + i] == 0) {
                    nullIndex = i;
                    break;
                }
            }
            String result = new String(buffer, bufferPos, nullIndex, StandardCharsets.UTF_8).trim();
            bufferPos += maxLength;
            totalBytesRead += maxLength;
            return result;
        }

        /**
         * Read null-terminated string from buffer. Used for reading strings in hierarchy data.
         *
         * @return String with null termination removed
         * @throws ParseException
         *             If read error occurs
         */
        public String readNullTerminatedString() throws ParseException, EOFException {
            StringBuilder sb = new StringBuilder();
            while (true) {
                ensureAvailable(1);
                byte b = buffer[bufferPos];
                bufferPos++;
                totalBytesRead++;
                if (b == 0) {
                    // Null terminator found
                    break;
                }
                sb.append((char) (b & 0xFF));
            }
            return sb.toString();
        }

        // ========================================================================================================================
        // Stream Operations
        // ========================================================================================================================
        /**
         * Read specified number of bytes into array
         *
         * @param bytes
         *            Byte array to fill
         * @throws ParseException
         *             If read error occurs or EOF reached
         */
        public void readFully(byte[] bytes) throws ParseException, EOFException {
            readFully(bytes, 0, bytes.length);
        }

        /**
         * Read specified number of bytes into array at given offset
         *
         * @param bytes
         *            Byte array to fill
         * @param offset
         *            Starting offset in the target array
         * @param length
         *            Number of bytes to read
         * @throws ParseException
         *             If read error occurs or EOF reached
         */
        public void readFully(byte[] bytes, int offset, int length) throws ParseException, EOFException {
            if (!isStreamBased) {
                // Buffer-based: can use ensureAvailable and copy directly
                ensureAvailable(length);
                System.arraycopy(buffer, bufferPos, bytes, offset, length);
                bufferPos += length;
                totalBytesRead += length;
            } else {
                // Stream-based: may need to read in chunks if length > buffer size
                int remaining = length;
                int destPos = offset;
                while (remaining > 0) {
                    int available = bufferLimit - bufferPos;
                    if (available == 0) {
                        // Fill buffer with at least 1 byte (or up to buffer size)
                        refillBuffer(Math.min(remaining, buffer.length));
                        available = bufferLimit - bufferPos;
                        if (available == 0) {
                            throw new EOFException("End of stream reached while reading fully");
                        }
                    }
                    int toCopy = Math.min(available, remaining);
                    System.arraycopy(buffer, bufferPos, bytes, destPos, toCopy);
                    bufferPos += toCopy;
                    destPos += toCopy;
                    totalBytesRead += toCopy;
                    remaining -= toCopy;
                }
            }
        }

        /**
         * Skip specified number of bytes in the stream
         *
         * @param count
         *            Number of bytes to skip
         * @return Number of bytes actually skipped
         * @throws ParseException
         *             If read error occurs
         */
        public int skipBytes(int count) throws ParseException {
            if (count <= 0) {
                return 0;
            }
            // For buffer-based reading, we can only skip what's available in buffer + stream
            int availableInBuffer = bufferLimit - bufferPos;
            if (count <= availableInBuffer) {
                // Can skip entirely within current buffer
                bufferPos += count;
                totalBytesRead += count;
                return count;
            }
            if (!isStreamBased) {
                // Buffer-only mode: can only skip what's available
                int actualSkipped = Math.min(count, availableInBuffer);
                bufferPos += actualSkipped;
                totalBytesRead += actualSkipped;
                return actualSkipped;
            }
            // Stream-based: skip what's in buffer, then skip in stream
            int skippedInBuffer = availableInBuffer;
            // Mark buffer as consumed
            bufferPos = bufferLimit;
            totalBytesRead += skippedInBuffer;
            int remainingToSkip = count - skippedInBuffer;
            long skippedInStream = 0;
            try {
                skippedInStream = inputStream.skip(remainingToSkip);
            } catch (java.io.IOException e) {
                throw new ParseException("IO error while skipping bytes in stream: " + e.getMessage(), e);
            }
            totalBytesRead += skippedInStream;
            // Reset buffer since we skipped in the stream
            bufferPos = 0;
            bufferLimit = 0;
            return skippedInBuffer + (int) skippedInStream;
        }

        /**
         * Skip to the end of a block given the section length and bytes already read
         *
         * @param sectionLength
         *            Total section length including the section length field
         * @param bytesAlreadyRead
         *            Number of bytes already read from this section
         * @return Number of bytes skipped
         * @throws ParseException
         *             If read error occurs
         */
        public int skipToBlockEnd(long sectionLength, long bytesAlreadyRead) throws ParseException {
            long remainingBytes = sectionLength - bytesAlreadyRead;
            if (remainingBytes > 0 && remainingBytes <= Integer.MAX_VALUE) {
                return skipBytes((int) remainingBytes);
            }
            return 0;
        }

        /**
         * Check if more data is available for reading
         *
         * @return true if more data is available, false if at end
         */
        public boolean hasMoreData() throws EOFException {
            if (bufferPos < bufferLimit) {
                return true;
            }
            if (!isStreamBased) {
                return false;
            }
            // For stream-based reading, try to read one byte to see if more data is available
            try {
                ensureAvailable(1);
                return bufferPos < bufferLimit;
            } catch (ParseException e) {
                return false;
            }
        }

        /**
         * Close the underlying stream
         *
         * @throws ParseException
         *             If close error occurs
         */
        public void close() throws ParseException {
            if (isStreamBased && inputStream != null) {
                try {
                    inputStream.close();
                } catch (java.io.IOException e) {
                    throw new ParseException("IO error while closing input stream: " + e.getMessage(), e);
                }
            }
        }
    }
}
