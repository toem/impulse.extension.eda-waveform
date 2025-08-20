package de.toem.impulse.serializer.vcd;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map; 
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import de.toem.impulse.cells.record.AbstractSignalProviderCell;
import de.toem.impulse.cells.record.Record;
import de.toem.impulse.cells.record.RecordProxy;
import de.toem.impulse.cells.record.RecordScope;
import de.toem.impulse.cells.record.RecordSignal;
import de.toem.impulse.i18n.I18n;
import de.toem.impulse.samples.IEventSamplesWriter;
import de.toem.impulse.samples.IFloatSamplesWriter;
import de.toem.impulse.samples.ILogicSamplesWriter;
import de.toem.impulse.samples.ISample;
import de.toem.impulse.samples.ISamples;
import de.toem.impulse.samples.ISamplesWriter;
import de.toem.impulse.samples.ITextSamplesWriter;
import de.toem.impulse.samples.adaptors.old.IPortProgress;
import de.toem.impulse.samples.domain.IDomainBase;
import de.toem.impulse.samples.domain.TimeBase;
import de.toem.impulse.samples.writer.SamplesWriter;
import de.toem.impulse.serializer.AbstractSingleDomainRecordReader;
import de.toem.impulse.serializer.IParsingRecordReader;
import de.toem.toolkits.core.Utils;
import de.toem.toolkits.pattern.element.ICell;
import de.toem.toolkits.pattern.element.ICover;
import de.toem.toolkits.pattern.element.serializer.ISerializerDescriptor;
import de.toem.toolkits.pattern.element.serializer.SingletonSerializerPreference.DefaultSerializerConfiguration;
import de.toem.toolkits.pattern.filter.FilterExpression;
import de.toem.toolkits.pattern.properties.IPropertyModel;
import de.toem.toolkits.pattern.registry.IRegistryObject;
import de.toem.toolkits.pattern.registry.RegistryAnnotation;
import de.toem.toolkits.pattern.systemLog.SystemLog;
import de.toem.toolkits.pattern.threading.IProgress;
import de.toem.toolkits.utils.serializer.ParseException;
import de.toem.impulse.usecase.eda.waveform.WaveformVariable;
import de.toem.toolkits.pattern.ide.ConfiguredConsoleStream;
import de.toem.toolkits.pattern.ide.IConsoleStream;
import de.toem.toolkits.pattern.ide.Ide;
import de.toem.toolkits.pattern.element.serializer.JavaSerializerPreference;
import de.toem.toolkits.pattern.simpleJava.ISimpleJava;
import de.toem.toolkits.pattern.element.Elements;
import de.toem.toolkits.pattern.threading.Progress;
/**
 * VcdReader: VCD (Value Change Dump) Waveform Importer for impulse
 *
 * This class implements a reader for the VCD format, enabling the impulse framework to import,
 * parse, and structure digital simulation waveforms from EDA tools. It supports incremental parsing
 * of large VCD files and maps VCD constructs to impulse's record and signal model.
 *
 * Technical Overview:
 * - Parses VCD header and value change sections, including hierarchical scopes and signal definitions.
 * - Supports logic, real, string, and event signal types; maps VCD variables to impulse signals.
 * - Uses a token-based buffer scanner for incremental, memory-efficient parsing.
 * - Handles large files with progress reporting and cancellation support.
 * - Builds impulse record and signal structures from VCD definitions automatically.
 * - Employs a fast lookup table for VCD identifiers and sample writers.
 *
 * Configurable Properties:
 * - hierarchy: Enables hierarchical grouping of signals by scope.
 * - empty: Controls retention of empty scopes.
 * - include/exclude: Filter expressions for signal selection.
 * - start, end, delay, scale: Control time range and transformation.
 * - Console logging configuration.
 *
 * Parsing Process:
 * - Reads input in buffered chunks and scans for VCD tokens.
 * - Processes commands, time markers, and value changes using a lookup table.
 * - Initializes record structure on first time marker or explicit command.
 * - Maps VCD identifiers to impulse signals and writers for efficient sample writing.
 * - Supports cancellation and progress feedback for integration with UI.
 * 
 * Copyright (c) 2013-2025 Thomas Haber
 * All rights reserved.
 */
@RegistryAnnotation(annotation = VcdReader.Annotation.class)
public class VcdReader extends AbstractSingleDomainRecordReader {

    public static class Annotation extends AbstractSingleDomainRecordReader.Annotation {
        public static final String id = "de.toem.impulse.reader.vcd";
        public static final String label = "VCD Reader";
        public static final String description = "";
        public static final String helpURL = "";
        public static final String defaultNamePattern = "\\.vcd$,\\.VCD$";
        public static final String formatType = "vcd";
        public static final String certificate = "YxwDcTBbUGoX55dzJYLYVcwkeYbjTaQ4VhODxCEfY7ExnE2ylazpEwuuq2EVmdJTgxpkFOEmAqkU6uVBl8aJVVrYkwPSzJaFm8Jr7Njkel6s32cE7YnIMRETtAegBG12pEoaVokZbyfN8n+x6wMQ4GM7T5AZBDPTuIhjJH3o8OxpgsHjUp4vFR3QGmwOna0dETtv1pK8dv2TUx6u5nwdrE3q/eQ9XErX/1LjZEUVUZg0FEl9YhoS5V9ASrb2qXEAysCS9foHYxdCbQ5xNyD2RPns6md3jwTKDpgaUgtYhvKJIZhcsGIqXAIba3y0vG8BWoUKbwWZXOj1uH723KE6GMbtTZEudHte";
    }

    // --- Static content and parsing definitions ---

    // Dump state for VCD parsing
    enum DumpState { Normal, DumpVars, DumpOn, DumpOff, DumpAll }

    // VCD command keywords
    enum Command {
        VAR, ENDDEFINITIONS, END, SCOPE, UPSCOPE, COMMENT, DATE, DUMPALL, DUMPOFF, DUMPON, DUMPVARS, VERSION, TIMESCALE, TIMEZERO
    }

    // Byte arrays for command keywords
    static byte[][] commands;

    // Static initialization for command keywords
    static {
        commands = new byte[Command.values().length][];
        for (int n = 0; n < Command.values().length; n++) {
            commands[n] = Command.values()[n].toString().toLowerCase().getBytes();
        }
    }

    // Pattern for timescale parsing
    private static Pattern PATTERN_TIMESCALE = Pattern.compile("\\s*(1|10|100)\\s*(fs|ps|ns|us|ms|s)\\s*");
    // Pattern for variable parsing
    private static Pattern PATTERN_VAR = Pattern.compile("\\s*(\\w+)\\s+(\\d+)\\s+([!-~]+)\\s+(.*)");

    // Token definitions for VCD parsing
    static final int TOKEN_COMMAND = 0x10;
    static final int TOKEN_TIME = 0x20;
    static final int TOKEN_VECTOR_CHANGE = 0x30;
    static final int TOKEN_REAL_CHANGE = 0x40;
    static final int TOKEN_WS = 0x50;
    static final int TOKEN_CHANGE2 = 0x60;
    static final int TOKEN_CHANGE4 = 0x70;
    static final int TOKEN_CHANGE16 = 0x80;
    static final int TOKEN_STRING_CHANGE = 0x90;
    static final int TOKEN_NONE = 0xf0;

    // Token lookup table for fast parsing
    static final int[] token = new int[256];

    // Static initialization for token lookup
    static {
        for (int i = 0; i < 256; i++)
            token[i] = TOKEN_NONE;
        token['$'] = TOKEN_COMMAND;
        token['#'] = TOKEN_TIME;
        token['b'] = TOKEN_VECTOR_CHANGE;
        token['B'] = TOKEN_VECTOR_CHANGE;
        token['r'] = TOKEN_REAL_CHANGE;
        token['R'] = TOKEN_REAL_CHANGE;
        token['s'] = TOKEN_STRING_CHANGE;
        token['S'] = TOKEN_STRING_CHANGE;
        token[' '] = TOKEN_WS;
        token['\t'] = TOKEN_WS;
        token['\n'] = TOKEN_WS | 1;
        token['\r'] = TOKEN_WS | 2;
        token['0'] = TOKEN_CHANGE2 | ISample.STATE_0_BITS;
        token['1'] = TOKEN_CHANGE2 | ISample.STATE_1_BITS;
        token['Z'] = TOKEN_CHANGE4 | ISample.STATE_Z_BITS;
        token['z'] = TOKEN_CHANGE4 | ISample.STATE_Z_BITS;
        token['X'] = TOKEN_CHANGE4 | ISample.STATE_X_BITS;
        token['x'] = TOKEN_CHANGE4 | ISample.STATE_X_BITS;
        token['L'] = TOKEN_CHANGE16 | ISample.STATE_L_BITS;
        token['l'] = TOKEN_CHANGE16 | ISample.STATE_L_BITS;
        token['H'] = TOKEN_CHANGE16 | ISample.STATE_H_BITS;
        token['h'] = TOKEN_CHANGE16 | ISample.STATE_H_BITS;
        token['U'] = TOKEN_CHANGE16 | ISample.STATE_U_BITS;
        token['u'] = TOKEN_CHANGE16 | ISample.STATE_U_BITS;
        token['W'] = TOKEN_CHANGE16 | ISample.STATE_W_BITS;
        token['w'] = TOKEN_CHANGE16 | ISample.STATE_W_BITS;
        token['-'] = TOKEN_CHANGE16 | ISample.STATE_D_BITS;
    }

    // Properties

    // Whether hierarchy resolution is enabled (SystemC)- contains split regEx if enabled 
    private String hierarchyResolver;
    // Whether vector resolution is enabled (for multi-bit signals))
    private boolean vectorResolver;
    // Whether empty scopes should be kept in the record structure
    private boolean keepEmptyScopes;
    // List of filter expressions to include signals during import
    private List<FilterExpression> includeSignals;
    // List of filter expressions to exclude signals during import
    private List<FilterExpression> excludeSignals;
    // Start time for importing samples (domain units)
    private long start = Long.MIN_VALUE;
    // End time for importing samples (domain units)
    private long end = Long.MAX_VALUE;
    // Delay to apply to all timestamps (domain units)
    private long delay = 0;
    // Stretch factor for timestamps
    private double dilate = 1;


    // Console for logging throughout the parsing process
    private IConsoleStream console;
    // Current dump state (used during parsing)
    private DumpState state = DumpState.Normal;
    // Domain base for time values (e.g., ns, us)
    private TimeBase timeBase = TimeBase.ns;
    // Offset to apply to all timestamps (domain units)
    private long timeZero = 0;
    // Current scope in the record hierarchy during parsing
    private ICell scope;
    // Current time (domain units)
    private long current = 0;

    // Map of VCD identifiers to their first assigned WaveformVariable
    private Map<String, WaveformVariable<String>> ids = new LinkedHashMap<String, WaveformVariable<String>>();
    // Map of scopes to lists of WaveformVariables defined within them
    private Map<ICell, List<WaveformVariable<String>>> vars = new LinkedHashMap<ICell, List<WaveformVariable<String>>>();

    // Map of VCD identifiers to impulse sample writers for writing parsed samples
    private Map<String, ISamplesWriter> samplesById = new LinkedHashMap<String, ISamplesWriter>();
    // Array index for fast lookup of sample writers by VCD identifier
    private ISamplesWriter[] samplesIndex;
    // Indicates whether the record and signal structure has been initialized
    private boolean initialized;
    // Base index for samplesIndex array
    private int samplesIndexBase;
    private boolean useMapLogged;

    // Maximum number of logic states supported (from impulse)
    private static final int MAX_STATES = ISamples.MAX_SCALE;
    // Buffer for storing parsed logic states during vector changes
    private byte[] statesBuffer = new byte[MAX_STATES];

    // ========================================================================================================================
    // Construct
    // ========================================================================================================================

    public VcdReader(ISerializerDescriptor descriptor, String contentName, String contentType, String cellType, String configuration,
            String[][] properties, InputStream in) {
        super(descriptor, configuration, properties, getPropertyModel(descriptor, null), in);
    }

    public VcdReader() {
        super();
    }

    // ========================================================================================================================
    // Property Model
    // ========================================================================================================================

    /**
     * Returns the property model for the VCD reader, including hierarchy, filtering, and console options.
     *
     * @param object   The serializer descriptor.
     * @param context  The context for property model creation.
     * @return         The property model.
     */
    static public IPropertyModel getPropertyModel(ISerializerDescriptor object, Object context) {

        boolean notPref = context != IRegistryObject.Preference.class;
        return IParsingRecordReader.getPropertyModel( PROP_EMPTY|PROP_HIERARCHY|PROP_VECTOR|(notPref?(PROP_INCLUDE|PROP_RANGE|PROP_TRANSFORM):0)).add(ConfiguredConsoleStream.getPropertyModel());

    }

    // ========================================================================================================================
    // Applicable
    // ========================================================================================================================

    /**
     * Checks if the reader is applicable for the given input.
     *
     * @param name         The content name.
     * @param contentType  The content type.
     * @param cellType     The cell type.
     * @param inputRequest The input request.
     * @return             APPLICABLE or NOT_APPLICABLE.
     */
    @Override
    public int isApplicable(String name, String contentType, String cellType, IInputRequest inputRequest) {
        if (inputRequest.text(32).trim().startsWith("$")) {
            return APPLICABLE;
        }
        return NOT_APPLICABLE;
    }


    // ========================================================================================================================
    // Supports
    // ========================================================================================================================

    /**
     * Checks if the reader supports the given request and context.
     *
     * @param request  The request type.
     * @param context  The context type.
     * @return         True if supported, false otherwise.
     */
    public static boolean supports(Object request, Object context) {
        int ir = request instanceof Integer ? ((Integer) request).intValue() : -1;
        if (SUPPORT_CONFIGURATION == ir && DefaultSerializerConfiguration.TYPE.equals(context))
            return true;
        return ir == (ir & (SUPPORT_PROPERTIES | SUPPORT_SOURCE));
    }

    public static ICell createJavaPreference() {
        
        JavaSerializerPreference p =  new  JavaSerializerPreference();
        p.setName(Annotation.label);
        p.description = Annotation.description;
        p.helpUrl = Annotation.helpURL;
        p.namePattern = Annotation.defaultNamePattern;
        p.formatType = Annotation.formatType;
        p.certificate = Annotation.certificate;
        p.impl = ISimpleJava.getClassSourceFromJar(VcdReader.class);
        p.javaBundle = "de.toem.impulse.base";
        p.cellType = "record";
        return p;
    }
    
    // ========================================================================================================================
    // Parser
    // ========================================================================================================================

    /**
     * Main parsing entry point. Reads and parses the VCD input stream, builds impulse records and signals.
     *
     * @param progress Progress reporting and cancellation.
     * @param rin      Input stream to parse.
     * @throws ParseException If parsing fails.
     */
    protected void parse(IProgress progress, InputStream rin) throws ParseException {

        if (in == null)
            return;

        // start time
        long started = Utils.millies();

        // buffer 
        byte[] buffer = new byte[1 << 16];
        int read = 0;
        int readTotal = 0;
        int available = 0;
        int used = 0;
        int wrapped = 0;
        boolean insertedFinalWs = false;

        // current scope
        this.scope = this.base;

        // Set up console logging
        console = new ConfiguredConsoleStream(Ide.DEFAULT_CONSOLE, getLabel(),ConfiguredConsoleStream.logging(getProperties()));
        console.info("VCD Reader initialized - parsing file");

        // ========================================================================================================================
        // Properties
        // ========================================================================================================================

        // resolver
        this.hierarchyResolver = getProperty("hierarchy"); 
        this.vectorResolver = Boolean.TRUE == getTypedProperty("vector");

        // exclude/include
        this.includeSignals = FilterExpression.createList(getProperty("include"), FilterExpression.TYPE_REGULAR | FilterExpression.TYPE_TEXT);
        this.excludeSignals = FilterExpression.createList(getProperty("exclude"), FilterExpression.TYPE_REGULAR | FilterExpression.TYPE_TEXT);
        // empty scopes
        this.keepEmptyScopes = Boolean.TRUE == getTypedProperty("empty");
        

        // ========================================================================================================================
        // Buffer handling
        // ========================================================================================================================
        try {
            InputStream in = decompressStream(rin);
            console.info("Stream decompression initialized");

            while ((read != -1 || available > 0) & ! progress.isCanceled() ) {

                // read dat into buffer
                read = in.read(buffer, wrapped, buffer.length - wrapped);
                readTotal += read > 0 ? read : 0;

                // parser awaits ws after final statement
                if (read == -1 && !insertedFinalWs) {
                    read = 1;
                    insertedFinalWs = true;
                    buffer[wrapped] = ' ';
                    console.info("Inserted final whitespace for parser termination");
                }

                // downstream parsing awaits the buffer completely filled from the beginning
                available = wrapped + (read > 0 ? read : 0);
                byte[] parseBuffer = buffer;
                if (available < buffer.length) {
                    parseBuffer = new byte[available];
                    System.arraycopy(buffer, 0, parseBuffer, 0, available);
                }

                // call the parser
                used = parse(parseBuffer);
                if (closed) {
                    console.info("Parsing completed - record closed");
                    return;
                }

                // send changed after block parsing
                if (initialized && used > 0)
                    changed(CHANGED_SIGNALS);

                // flush and set progress
                if (progress != null) {
                    flushAndSetProgress(progress);

                }
                
                // if no additional data is available and the available could not be used, we have a problem   
                if (read == -1 && available > 0 && used == 0) {
                    throw new ParseException( "Unable to parse remaining data in buffer");
                }

                // copies the unused data to the beginning of the buffer
                wrapped = available - used;
                System.arraycopy(buffer, used, buffer, 0, wrapped);
            }
        } catch (Throwable e) {
            if (!(e instanceof ParseException))
                e =  new ParseException("Main parse error: " + e.getMessage(), e); 
            
            // extract the error line from the buffer if available
            if (e instanceof ParseException && ((ParseException) e).position >= 0 && buffer != null) {
                int pos = ((ParseException) e).position;
                int lineStart = pos, lineEnd = pos;
                // Find start of line
                while (lineStart > 0 && buffer[lineStart - 1] != '\n') {
                    lineStart--;
                }
                // Find end of line
                while (lineEnd < buffer.length && buffer[lineEnd] != '\n') {
                    lineEnd++;
                }
                String snippetLine = new String(buffer, lineStart, lineEnd - lineStart);
                StringBuilder snippetWithMarker = new StringBuilder(snippetLine);
                int markerPos = pos - lineStart;
                if (markerPos >= 0 && markerPos <= snippetWithMarker.length()) {
                    snippetWithMarker.insert(markerPos, '|');
                }
                ((ParseException) e).snippet =  snippetWithMarker.toString();
            }            
            console.error(e); 
            throw (ParseException) e;

        } finally {

            // close
            if (!closed) {
                close(current + 1);
                console.info("Record close at position ", current + 1);
            }
            console.major("Parsing completed in ", (Utils.millies() - started), " ms");
        }
    }

    /**
     * Parses a buffer of VCD data and processes tokens, commands, and value changes.
     *
     * @param buffer  The buffer to parse.
     * @return        Number of bytes used from the buffer.
     * @throws ParseException If parsing fails.
     */
    private int parse(byte[] buffer) throws ParseException {

        final int length = buffer.length;
        int n = 0;
        int used = 0;

        try {

            for (; n < length; n++) {
                final byte b = buffer[n];
                final int sel = token[b & 0xff];
                switch (sel & 0xf0) {
                case TOKEN_WS:
                    continue;
                case TOKEN_TIME:
                    used = parseTime(buffer, n);

                    // initialize
                    if (!initialized) {

                        // initialize
                        console.info("Initializing record structure on first time marker");
                        initialize();

                        // open
                        if (current >= start) {
                            if (!open(start != Long.MIN_VALUE ? start : current))
                                throw new ParseException( "Failed to initialize record for reading",n,null);
                            opened = true;
                            console.info("Record opened at position ", current);
                        }
                        return n + used;
                    }
                    // open
                    else if (!opened) {
                        if (current >= start) {
                            if (!open(start != Long.MIN_VALUE ? start : current))
                                throw new ParseException( "Failed to initialize record for reading",n,null);
                            opened = true;
                            console.info("Record opened at position ", current);
                        }
                    } else if (!closed) {
                        if (current > end) {
                            close(end);
                            console.info("Record closed at end position ", end);
                            return n + used;
                        }
                    }
                    break;
                case TOKEN_VECTOR_CHANGE:
                    if (!initialized)
                        throw new ParseException( "Record not initialized - cannot parse value changes",n,null);
                    used = opened ? parseVectorChange(buffer, n) : skipChange(buffer, n);
                    break;
                case TOKEN_CHANGE2:
                    if (!initialized)
                        throw new ParseException( "Record not initialized - cannot parse value changes",n,null);
                    used = opened ? parseL2Change(buffer, n, (byte) (sel & 0xf)) : skipChange(buffer, n);
                    break;
                case TOKEN_CHANGE4:
                    if (!initialized)
                        throw new ParseException( "Record not initialized - cannot parse value changes",n,null);
                    used = opened ? parseL4Change(buffer, n, (byte) (sel & 0xf)) : skipChange(buffer, n);
                    break;
                case TOKEN_CHANGE16:
                    if (!initialized)
                        throw new ParseException( "Record not initialized - cannot parse value changes",n,null);
                    used = opened ? parseL16Change(buffer, n, (byte) (sel & 0xf)) : skipChange(buffer, n);
                    break;
                case TOKEN_REAL_CHANGE:
                    if (!initialized)
                        throw new ParseException( "Record not initialized - cannot parse value changes",n,null);
                    used = opened ? parseRealChange(buffer, n) : skipChange(buffer, n);
                    break;
                case TOKEN_STRING_CHANGE:
                    if (!initialized)
                        throw new ParseException( "Record not initialized - cannot parse value changes",n,null);
                    used = opened ? parseStringChange(buffer, n) : skipChange(buffer, n);
                    break;
                case TOKEN_COMMAND:
                    used = parseCommand(buffer, n);
                    if (initialized) { // chance to break after header
                        console.info("Header parsing completed - continuing with value changes");
                        return n + used + 1;
                    }
                    break;

                case TOKEN_NONE:
                    throw new ParseException( "Invalid character encountered in VCD file",n,null);
                }

                if (used == 0)
                    return n;
                n += used;
            }
            return n;
        } catch (Throwable e) {
            if (e instanceof ParseException) 
                throw (ParseException) e;
            throw new ParseException("Parsing error at position " + n + ": " + e.getMessage(), e,n,null);
        } finally { 
          
        }
    }

    // ========================================================================================================================
    // Initialize
    // ========================================================================================================================

    /**
     * Initializes the impulse record and signal structure from parsed VCD definitions.
     */
    private void initialize() {
        console.info("Initializing impulse record from VCD definitions");

        initRecord("VCD Record", timeBase);

        // Process VCD variables and create impulse signals
        WaveformVariable.identifyGroups(vars,vectorResolver);
        WaveformVariable.createSignals(vars, (Record) root, timeBase, this.includeSignals, this.excludeSignals);
        samplesById = WaveformVariable.createWriters(vars, timeBase, this);

        console.info("Created ", samplesById.size(), " signal writers");

        // Parse time range and transformation properties
        this.start = timeBase.parseMultiple(getProperty("start"), IDomainBase.PARSE_BIG | IDomainBase.PARSE_DOMAINBASE, this.start).longValue();
        this.end = timeBase.parseMultiple(getProperty("end"), IDomainBase.PARSE_BIG | IDomainBase.PARSE_DOMAINBASE, this.end).longValue();
        this.delay = timeBase.parseMultiple(getProperty("delay"), IDomainBase.PARSE_BIG | IDomainBase.PARSE_DOMAINBASE, this.delay).longValue();
        this.dilate = Utils.parseDouble(getProperty("dilate"), this.dilate);
        this.delay += timeZero;
        this.current = dilate == 1.0 ? 0 + delay : (long) ((0  + delay) * dilate);


        // Build fast lookup array for VCD identifiers if feasible
        int max = 0;
        int min = Integer.MAX_VALUE;
        for (String id : samplesById.keySet()) {
            if (index(id) > max)
                max = index(id);
            if (index(id) < min)
                min = index(id);
        }
        try {
            long count = (long) max + 1 - min;
            // Only create array if reasonable size
            if (count > 0 && count < 16 * 1024 * 1024) {
                samplesIndex = new ISamplesWriter[(int) count];
                samplesIndexBase = min;
                SamplesWriter.adjustBufferGeometry();
                // Populate lookup array
                for (String id : samplesById.keySet()) {
                    samplesIndex[index(id) - samplesIndexBase] = samplesById.get(id);
                }
            }
        } catch (Throwable e) {
            // Fall back to map lookup on any error
        }

        // Remove empty scopes if configured
        if (!keepEmptyScopes) {
            console.info("Removing empty scopes from record structure");
            removeEmptyScopes(base, false);
        }

        // Build hierarchical signal organization if enabled
        if (!Utils.isEmpty(hierarchyResolver)) {
            console.info("Building hierarchical signal organization");
            resolveHierarchies(base, hierarchyResolver,"Hierarchy");    
        }
        changed(CHANGED_RECORD);
        initialized = true;
        console.info("Record initialization completed");
    }

    // ========================================================================================================================
    // Time/Value Parser
    // ========================================================================================================================

    /**
     * Parses a VCD time marker and updates the current time.
     *
     * @param buffer  The buffer containing the time marker.
     * @param n       The position in the buffer.
     * @return        Number of bytes used.
     * @throws ParseException If parsing fails.
     */
    private int parseTime(byte[] buffer, int n) throws ParseException {
        final int length = buffer.length;
        long time = 0;
        // Parse numeric characters following '#' to build time value
        for (int i = n + 1; i < length; i++) {
            final byte b = buffer[i];
            if (b > '9' || b < '0') {
                // Apply dilate, offset, and delay transformations
                current = dilate == 1.0 ? time + delay : (long) ((time  + delay) * dilate);
                changed(CHANGED_CURRENT, current);
                console.log("Time marker parsed:", time, "current:", current);
                return i - n;
            } else
                time = time * 10 + (b - '0');
        }
        return 0;
    }

    /**
     * Skips a value change line in the buffer.
     *
     * @param buffer  The buffer containing the change.
     * @param n       The position in the buffer.
     * @return        Number of bytes used.
     * @throws ParseException If parsing fails.
     */
    private int skipChange(byte[] buffer, int n) throws ParseException {
        final int length = buffer.length;
        // Skip to end of line when record is not yet opened
        for (int i = n + 1; i < length; i++) {
            if (buffer[i] == '\n')
                return i - n;
        }
        return 0;
    }

    /**
     * Parses a 2-state logic value change.
     *
     * @param buffer  The buffer containing the change.
     * @param n       The position in the buffer.
     * @param state   The logic state.
     * @return        Number of bytes used.
     * @throws ParseException If parsing fails.
     */
    private int parseL2Change(byte[] buffer, int n, byte state) throws ParseException {
        final int length = buffer.length;
        int index = 0;
        // Build VCD identifier index from printable characters
        for (int i = n + 1; i < length; i++) {
            final byte b = buffer[i];
            if (b > '~' || b < '!') {
                // Get writer for this identifier and write sample
                ISamplesWriter writer = getWriter(index, buffer, n + 1, i - n - 1);
                if (writer instanceof ILogicSamplesWriter) {
                    ILogicSamplesWriter logicWriter = (ILogicSamplesWriter) writer;
                    // Use full state for multi-bit signals, reduced state for single-bit
                    logicWriter.write(current, false, ISample.STATE_LEVEL_2, logicWriter.getScale() > 1 ? (byte) ISample.STATE_0_BITS : state,
                            state);
                } else if (writer instanceof IEventSamplesWriter)
                    ((IEventSamplesWriter) writer).write(current, false);
                return i - n;
            } else
                // Convert printable character to index value
                index = index * 100 + (b - 0x20);
        }
        return 0;
    }

    /**
     * Parses a 4-state logic value change.
     *
     * @param buffer  The buffer containing the change.
     * @param n       The position in the buffer.
     * @param state   The logic state.
     * @return        Number of bytes used.
     * @throws ParseException If parsing fails.
     */
    private int parseL4Change(byte[] buffer, int n, byte state) throws ParseException {
        final int length = buffer.length;
        int index = 0;
        // Build VCD identifier index from printable characters
        for (int i = n + 1; i < length; i++) {
            final byte b = buffer[i];
            if (b > '~' || b < '!') {
                // Get writer for this identifier and write sample
                ISamplesWriter writer = getWriter(index, buffer, n + 1, i - n - 1);
                if (writer instanceof ILogicSamplesWriter) {
                    ILogicSamplesWriter logicWriter = (ILogicSamplesWriter) writer;
                    // Mark as X-state if needed, use appropriate state level
                    logicWriter.write(current, state == ISample.STATE_X_BITS, ISample.STATE_LEVEL_4,
                            logicWriter.getScale() > 1 ? (byte) ISample.STATE_0_BITS : state, state);
                } else if (writer instanceof IEventSamplesWriter)
                    ((IEventSamplesWriter) writer).write(current, state == ISample.STATE_X_BITS);
                return i - n;
            } else
                index = index * 100 + (b - 0x20);
        }
        return 0;
    }

    /**
     * Parses a 16-state logic value change.
     *
     * @param buffer  The buffer containing the change.
     * @param n       The position in the buffer.
     * @param state   The logic state.
     * @return        Number of bytes used.
     * @throws ParseException If parsing fails.
     */
    private int parseL16Change(byte[] buffer, int n, byte state) throws ParseException {
        final int length = buffer.length;
        int index = 0;
        // Build VCD identifier index from printable characters
        for (int i = n + 1; i < length; i++) {
            final byte b = buffer[i];
            if (b > '~' || b < '!') {
                // Get writer for this identifier and write sample
                ISamplesWriter writer = getWriter(index, buffer, n + 1, i - n - 1);
                if (writer instanceof ILogicSamplesWriter) {
                    ILogicSamplesWriter logicWriter = (ILogicSamplesWriter) writer;
                    // Write 16-state logic sample
                    logicWriter.write(current, false, ISample.STATE_LEVEL_16, logicWriter.getScale() > 1 ? (byte) ISample.STATE_0_BITS : state,
                            state);
                } else if (writer instanceof IEventSamplesWriter)
                    ((IEventSamplesWriter) writer).write(current, false);

                return i - n;
            } else
                index = index * 100 + (b - 0x20);
        }
        return 0;
    }

    /**
     * Parses a vector logic value change.
     *
     * @param buffer  The buffer containing the change.
     * @param n       The position in the buffer.
     * @return        Number of bytes used.
     * @throws ParseException If parsing fails.
     */
    private int parseVectorChange(byte[] buffer, int n) throws ParseException {
        final int length = buffer.length;
        int i = n + 1;
        int states = 0;
        int first = 0;

        int level = ISample.STATE_LEVEL_2;
        boolean tag = false;

        // Read vector states into buffer
        readStates: while (i < length && states < MAX_STATES) {
            int sel = token[buffer[i]];
            switch (sel & 0xf0) {
            case TOKEN_CHANGE2:
                statesBuffer[states++] = (byte) (sel & 0xf);
                break;
            case TOKEN_CHANGE4:
                statesBuffer[states++] = (byte) (sel & 0xf);
                // Upgrade to 4-state level if needed
                if (level < ISample.STATE_LEVEL_4)
                    level = ISample.STATE_LEVEL_4;
                // Mark as X-state if any bit is X
                if ((sel & 0xf) == ISample.STATE_X_BITS)
                    tag = true;
                break;
            case TOKEN_CHANGE16:
                statesBuffer[states++] = (byte) (sel & 0xf);
                level = ISample.STATE_LEVEL_16;
                break;
            case TOKEN_WS:
                i++;
                break readStates;
            default:
                throw new ParseException("Invalid logic state in vector change",i,null);
            }
            i++;
        }

        // Skip additional whitespace between vector and identifier
        while (i < length) {
            final byte b = buffer[i];
            if (b != ' ' && b != '\t')
                break;
            i++;
        }

        // Parse VCD identifier and write vector sample
        int index = 0;
        int m = i;
        while (i < length) {
            final byte b = buffer[i];

            if (b > '~' || b < '!') {
                ISamplesWriter writer = getWriter(index, buffer, m, i - m);
                if (writer instanceof ILogicSamplesWriter) {
                    ILogicSamplesWriter logicWriter = (ILogicSamplesWriter) writer;
                    // Trim vector to signal width if needed
                    if (states > logicWriter.getScale())
                        first += states - logicWriter.getScale();

                    // Find preceding state for compression
                    byte preceding = 0;
                    if (states < logicWriter.getScale() && statesBuffer[first] == ISample.STATE_1_BITS)
                        preceding = ISample.STATE_0_BITS;
                    else {
                        preceding = statesBuffer[first];
                        first++;
                    }
                    // Skip redundant preceding states
                    while (first < states) {
                        if (statesBuffer[first] == preceding)
                            first++;
                        else
                            break;
                    }
                    // Write compressed or full vector
                    if (states - first == 0)
                        logicWriter.write(current, tag, level, preceding);
                    else
                        logicWriter.write(current, tag, level, preceding, statesBuffer, first, states - first);

                } else if (writer instanceof IEventSamplesWriter)
                    ((IEventSamplesWriter) writer).write(current, tag);

                return i - n;
            } else
                index = index * 100 + (b - 0x20);
            i++;
        }
        return 0;
    }

    /**
     * Parses a real (floating-point) value change.
     *
     * @param buffer  The buffer containing the change.
     * @param n       The position in the buffer.
     * @return        Number of bytes used.
     * @throws ParseException If parsing fails.
     */
    private int parseRealChange(byte[] buffer, int n) throws ParseException {
        final int length = buffer.length;
        int i = n + 1;
        double value = 0;
        boolean tag = false;

        // Parse floating-point value until whitespace
        while (i < length) {
            final byte b = buffer[i];
            if (b == ' ' || b == '\t') {
                try {
                    value = Double.parseDouble(new String(buffer, n + 1, i - n));
                } catch (Throwable e) {
                    // Keep default value on parse error
                }
                break;
            }
            i++;
        }

        // Skip whitespace between value and identifier
        while (i < length) {
            final byte b = buffer[i];
            if (b != ' ' && b != '\t')
                break;
            i++;
        }

        // Parse VCD identifier and write real sample
        int index = 0;
        int m = i;
        while (i < length) {
            final byte b = buffer[i];

            if (b > '~' || b < '!') {
                ISamplesWriter writer = getWriter(index, buffer, m, i - m);
                if (writer instanceof IFloatSamplesWriter) {
                    ((IFloatSamplesWriter) writer).write(current, tag, value);
                }
                return i - n;
            } else
                index = index * 100 + (b - 0x20);
            i++;
        }
        return 0;

    }

    /**
     * Parses a string value change.
     *
     * @param buffer  The buffer containing the change.
     * @param n       The position in the buffer.
     * @return        Number of bytes used.
     * @throws ParseException If parsing fails.
     */
    private int parseStringChange(byte[] buffer, int n) throws ParseException {
        final int length = buffer.length;
        int i = n + 1;
        String value = "";
        boolean tag = false;

        // Parse string value until whitespace
        while (i < length) {
            final byte b = buffer[i];
            if (b == ' ' || b == '\t') {
                try {
                    value = new String(buffer, n + 1, i - n);
                } catch (Throwable e) {
                    // Keep empty string on error
                }
                break;
            }
            i++;
        }

        // Skip whitespace between value and identifier
        while (i < length) {
            final byte b = buffer[i];
            if (b != ' ' && b != '\t')
                break;
            i++;
        }

        // Parse VCD identifier and write string sample
        int index = 0;
        int m = i;
        while (i < length) {
            final byte b = buffer[i];

            if (b > '~' || b < '!') {
                ISamplesWriter writer = getWriter(index, buffer, m, i - m);
                if (writer instanceof ITextSamplesWriter) {
                    ((ITextSamplesWriter) writer).write(current, tag, value);
                }
                return i - n;
            } else
                index = index * 100 + (b - 0x20);
            i++;
        }
        return 0;
    }

    // ========================================================================================================================
    // Command Parser
    // ========================================================================================================================

    /**
     * Parses a VCD command and its parameters.
     *
     * @param buffer  The buffer containing the command.
     * @param n       The position in the buffer.
     * @return        Number of bytes used.
     * @throws ParseException If parsing fails.
     */
    private int parseCommand(byte[] buffer, int n) throws ParseException {
        final int length = buffer.length;
        boolean more = false;
        // Try to match each known command
        for (Command command : Command.values()) {

            // Match command name byte-by-byte
            int i = n;
            boolean skip = false;
            byte[] bytes = commands[command.ordinal()];
            for (byte b : bytes) {
                i++;
                if (i >= length) {
                    more = true;
                    skip = true;
                    break;
                }
                if (b != buffer[i]) {
                    skip = true;
                    break;
                }
            }
            if (skip)
                continue;

            // Process matched command
            i++;
            int used = 0;
            String[] parameters;
            switch (command) {
            case VAR:
                // Parse variable definition
                used = parseVarParameters(buffer, i, parameters = new String[7]);
                if (used == 0)
                    return 0;
                if (parameters[0] == null || parameters[1] == null || parameters[2] == null || parameters[3] == null || parameters[3].isEmpty())
                    throw new ParseException( "Invalid parameter count in variable definition",i,null);

                // Create waveform variable from parsed parameters
                WaveformVariable<String> var = new WaveformVariable<String>();
                var.name = parameters[3];
                var.handle = parameters[2]; // VCD identifier
                // Map VCD type to impulse data type
                var.dataType = "event".equals(parameters[0]) ? ISample.DATA_TYPE_ENUM
                        : "real".equals(parameters[0]) ? ISample.DATA_TYPE_FLOAT
                                : "string".equals(parameters[0]) ? ISample.DATA_TYPE_TEXT : ISample.DATA_TYPE_LOGIC;
                var.description = parameters[0];
                if (var.dataType == ISample.DATA_TYPE_LOGIC)
                    var.scale = Utils.parseInt(parameters[1], -1);
                var.scope = scope;

                // vector resolver
                var.idx0 = Utils.parseInt(parameters[5], -1); // High index
                var.idx1 = Utils.parseInt(parameters[6], -1); // Low index
                if (var.idx0 >= 0 || var.idx1 >= 0) {
                    var.idxname = parameters[4];
                }
                // Ensure idx0 >= idx1 for bit ranges
                if (var.idx1 > var.idx0) {
                    int swap = var.idx0;
                    var.idx0 = var.idx1;
                    var.idx1 = swap;
                }

                // Mark as shared if identifier already exists
                if (ids.containsKey(var.handle)) {
                    var.shared = true;
                    ids.get(var.handle).shared = true;
                }
                // Validate data type constraints
                if (var.dataType == ISample.DATA_TYPE_FLOAT && var.idx0 >= 0)
                    throw new ParseException( "Real data type cannot have vector indices",i,null);
                if (var.dataType == ISample.DATA_TYPE_TEXT && var.idx0 >= 0)
                    throw new ParseException( "String data type cannot have vector indices",i,null);
                // if (var.idx0 >= 0 && var.idx1 == -1 && var.scale != 1)
                // throw new ParseException( "Invalid scale");
                // if (var.idx0 >= 0 && var.idx1 >= 0 && var.scale != (var.idx0 - var.idx1 + 1))
                // throw new ParseException( "Invalid scale");
                if (ids.containsKey(var.handle)) {
                    if (var.scale != ids.get(var.handle).scale)
                        throw new ParseException( "Shared identifiers must have the same scale",i,null); 
                } else
                    ids.put(var.handle, var);
                // Add to scope's variable list
                if (!vars.containsKey(scope))
                    vars.put(scope, new ArrayList<WaveformVariable<String>>());
                if (!vars.get(scope).contains(var))
                    vars.get(scope).add(var);
                console.log(command, Utils.commarize(parameters));
                break;
            case ENDDEFINITIONS:
                used = parseParameterBlock(buffer, i, parameters = new String[1]);
                if (used == 0)
                    return 0;
                console.log(command, Utils.commarize(parameters));
            case END:
                break;
            case SCOPE:
                // Disable hierarchy resolution if nested scopes found
                if (this.scope != this.base)
                    hierarchyResolver = null;
                used = parseParameters(buffer, i, parameters = new String[2]);
                if (used == 0)
                    return 0;
                if (parameters[0] == null || parameters[1] == null)
                    throw new ParseException( "Invalid parameter count in scope definition",i,null);
                // Create or find scope
                ICell scope = this.scope.getChild(parameters[1]);
                if (!(scope instanceof RecordScope)) {
                    scope = new RecordScope();
                    scope.setName(parameters[1]);
                    this.scope.addChild(scope);
                }
                this.scope = scope;
                console.log(command, Utils.commarize(parameters));
                break;
            case UPSCOPE:
                used = parseParameterBlock(buffer, i, parameters = new String[1]);
                if (used == 0)
                    return 0;
                // Move up one level in scope hierarchy
                if (this.scope.getCellContainer() != null)
                    this.scope = this.scope.getCellContainer();
                console.log(command, Utils.commarize(parameters));
                break;
            case COMMENT:
                used = parseParameterBlock(buffer, i, parameters = new String[1]);
                if (used == 0)
                    return 0;
                console.log(command, Utils.commarize(parameters));
                break;
            case DATE:
                used = parseParameterBlock(buffer, i, parameters = new String[1]);
                if (used == 0)
                    return 0;
                console.log(command, Utils.commarize(parameters));
                break;
            case DUMPALL:
                break;
            case DUMPOFF:
                break;
            case DUMPON:
                break;
            case DUMPVARS:
                // Initialize record structure on dumpvars command
                if (!initialized) {

                    // initialize
                    initialize();
                    if (current >= start) {
                        if (!open(start != Long.MIN_VALUE ? start : current))
                            throw new ParseException( "Failed to initialize record for reading",i,null);
                        opened = true;
                    }
                }
                break;
            case VERSION:
                used = parseParameterBlock(buffer, i, parameters = new String[1]);
                if (used == 0)
                    return 0;
                // Enable hierarchy for SystemC VCD files
                console.log(command, Utils.commarize(parameters));
                break;
            case TIMESCALE:
                used = parseParameters(buffer, i, parameters = new String[2], PATTERN_TIMESCALE);
                if (used == 0)
                    return 0;
                if (parameters[0] == null || parameters[1] == null)
                    throw new ParseException( "Invalid parameter count in timescale definition",i,null);
                // Parse timescale factor and unit
                int factor = Utils.parseInt(parameters[0], -1);
                if (factor == 10)
                    parameters[1] += "10";
                else if (factor == 100)
                    parameters[1] += "100";
                timeBase = TimeBase.parse(parameters[1]);
                if (timeBase == null)
                    throw new ParseException( "Invalid timescale unit specified",i,null);
                // Update existing writers with new timebase
                if (!samplesById.isEmpty())
                    for (ISamplesWriter writer : samplesById.values())
                        ((SamplesWriter) writer).setDomainBase(timeBase);   /// uis this needed ?????
                console.log(command, Utils.commarize(parameters));
                break;
            case TIMEZERO: 
                used = parseParameterBlock(buffer, i, parameters = new String[1]);
                if (used == 0)
                    return 0;
                // Set time offset from timezero command
                timeZero = Utils.parseLong(parameters[0], 0);
                console.log(command, Utils.commarize(parameters));
                break;
            }
            return i - n + used;
        }
        if (!more)
            throw new ParseException( "No valid VCD command found",n,null);
        return 0;
    }

    /**
     * Parses parameters from a buffer, splitting by whitespace.
     *
     * @param buffer      The buffer containing parameters.
     * @param n           The position in the buffer.
     * @param parameters  The output array for parameters.
     * @return            Number of bytes used.
     * @throws ParseException If parsing fails.
     */
    private int parseParameters(byte[] buffer, int n, String[] parameters) throws ParseException {
        for (int i = n; i < buffer.length; i++) {
            if (buffer[i] == '$') {
                if (buffer.length > i + 3 && buffer[i + 1] == 'e' && buffer[i + 2] == 'n' && buffer[i + 3] == 'd') {
                    String string = new String(buffer, n, i - n);
                    if (parameters != null) {
                        String[] splitted = string.trim().split("\\s+");
                        for (int j = 0; j < splitted.length && j < parameters.length; j++)
                            parameters[j] = splitted[j];
                    }
                    return i + 3 - n;
                }
            }
        }
        return 0;
    }

    /**
     * Parses parameters from a buffer using a regular expression pattern.
     *
     * @param buffer      The buffer containing parameters.
     * @param n           The position in the buffer.
     * @param parameters  The output array for parameters.
     * @param pattern     The regex pattern to use.
     * @return            Number of bytes used.
     * @throws ParseException If parsing fails.
     */
    private int parseParameters(byte[] buffer, int n, String[] parameters, Pattern pattern) throws ParseException {
        for (int i = n; i < buffer.length; i++) {
            if (buffer[i] == '$') {
                if (buffer.length > i + 3 && buffer[i + 1] == 'e' && buffer[i + 2] == 'n' && buffer[i + 3] == 'd') {
                    String string = new String(buffer, n, i - n);
                    if (parameters != null) {
                        Matcher m = pattern.matcher(string);
                        if (m.find()) {
                            for (int j = 0; j < m.groupCount() && j < parameters.length; j++)
                                parameters[j] = m.group(j + 1);
                        }
                    }
                    return i + 3 - n;
                }
            }
        }
        return 0;
    }

    /**
     * Parses variable parameters from a buffer using a regular expression pattern.
     *
     * @param buffer      The buffer containing parameters.
     * @param n           The position in the buffer.
     * @param parameters  The output array for parameters.
     * @param pattern     The regex pattern to use.
     * @return            Number of bytes used.
     * @throws ParseException If parsing fails.
     */
    private int parseVarParameters(byte[] buffer, int n, String[] parameters) throws ParseException {
        for (int i = n; i < buffer.length; i++) {
            if (buffer[i] == '$') {
                if (buffer.length > i + 3 && buffer[i + 1] == 'e' && buffer[i + 2] == 'n' && buffer[i + 3] == 'd') {
                    if (parameters != null && parameters.length == 7) {
                        String string = new String(buffer, n, i - n);
                        Matcher m = PATTERN_VAR.matcher(string);
                        if (m.find()) {
                            parameters[0] = m.group(0 + 1);
                            parameters[1] = m.group(1 + 1);
                            parameters[2] = m.group(2 + 1);
                            String rem = parameters[3] = m.group(3 + 1).trim().replaceAll("\\s+\\[", "[");
                            int vec0Idx = rem.lastIndexOf('[');
                            if (vec0Idx > 0) {
                                parameters[4] = rem.substring(0, vec0Idx).trim();
                                int dimIdx = rem.indexOf(':', vec0Idx);
                                int vec1Idx = rem.indexOf(']', vec0Idx);
                                if (vec1Idx > 0) {
                                    if (dimIdx > 0) {
                                        parameters[5] = rem.substring(vec0Idx + 1, dimIdx).trim();
                                        parameters[6] = rem.substring(dimIdx + 1, vec1Idx).trim();
                                    } else
                                        parameters[5] = rem.substring(vec0Idx + 1, vec1Idx).trim();
                                }
                            } else
                                parameters[3] = rem;
                            ;
                        }
                    }
                    return i + 3 - n;
                }
            }
        }
        return 0;
    }

    /**
     * Parses a block of parameters from a buffer.
     *
     * @param buffer      The buffer containing parameters.
     * @param n           The position in the buffer.
     * @param parameters  The output array for parameters.
     * @return            Number of bytes used.
     * @throws ParseException If parsing fails.
     */
    private int parseParameterBlock(byte[] buffer, int n, String[] parameters) throws ParseException {
        for (int i = n; i < buffer.length; i++) {
            if (buffer[i] == '$') {
                if (buffer.length > i + 3 && buffer[i + 1] == 'e' && buffer[i + 2] == 'n' && buffer[i + 3] == 'd') {
                    String string = new String(buffer, n, i - n);
                    if (parameters != null && parameters.length == 1)
                        parameters[0] = string.trim();
                    return i + 3 - n;
                }
            }
        }
        return 0;
    }


    // ========================================================================================================================
    // Helper
    // ========================================================================================================================
    
    /**
     * Gets the sample writer for a given VCD identifier.
     *
     * @param index   The numeric index of the identifier.
     * @param buffer  The buffer containing the identifier.
     * @param pos     The position in the buffer.
     * @param length  The length of the identifier.
     * @return        The corresponding ISamplesWriter, or null if not found.
     * @throws ParseException If writer is not found.
     */
    private ISamplesWriter getWriter(int index, byte[] buffer, int pos, int length) throws ParseException {
        // Try fast array lookup first
        if (samplesIndex != null) {
            final int sidx = index - samplesIndexBase;
            if (sidx >= 0 && sidx < samplesIndex.length) 
                return  samplesIndex[index - samplesIndexBase];
        }
        if (useMapLogged == false){
            console.info("Use map lookup");
            useMapLogged = true; // Log only once
        }
        // Fall back to map lookup
        String key = new String(buffer, pos, length);
        ISamplesWriter writer = samplesById.get(key);
        return writer;
    }

    /**
     * Computes a numeric index for a VCD identifier string.
     *
     * @param id  The VCD identifier.
     * @return    The numeric index.
     */
    private int index(String id) {
        int index = 0;
        // Convert each byte to base-100 index value
        for (byte b : id.getBytes()) { 
            index = index * 100 + (b - 0x20); 
        }
        return index;
    }

}