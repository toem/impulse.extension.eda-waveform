package de.toem.impulse.extension.eda.waveform.fst;

import java.io.File;
import java.io.InputStream;
import java.util.Arrays;

import de.toem.impulse.extension.eda.waveform.i18n.I18n;
import de.toem.impulse.serializer.AbstractLazyFluxReader;
import de.toem.impulse.serializer.IParsingRecordReader;
import de.toem.toolkits.core.Utils;
import de.toem.toolkits.pattern.bundles.Bundles;
import de.toem.toolkits.pattern.element.serializer.ISerializerDescriptor;
import de.toem.toolkits.pattern.element.serializer.ICellSerializer.IInputRequest;
import de.toem.toolkits.pattern.element.serializer.SingletonSerializerPreference.DefaultSerializerConfiguration;
import de.toem.toolkits.pattern.ide.ConfiguredConsoleStream;
import de.toem.toolkits.pattern.properties.IPropertyModel;
import de.toem.toolkits.pattern.properties.PropertyModel;
import de.toem.toolkits.pattern.registry.IRegistryObject;
import de.toem.toolkits.pattern.registry.RegistryAnnotation;
import de.toem.toolkits.utils.natives.NativeExtensionBuilder;

/**
 * Native FST (Fast Signal Trace) file reader implementation for the impulse framework.
 *
 * This reader provides high-performance FST file processing through a native C implementation
 * that leverages the official FST API library. It offers superior parsing speed and memory efficiency
 * compared to the pure Java {@link FstReader} implementation, making it ideal for processing large
 * simulation datasets and production workflows.
 *
 * Key features of this implementation include:
 * - Native performance through optimized C code with the official FST library
 * - Complete FST format support including compression, dynamic aliasing, and hierarchical structures
 * - Lazy loading capabilities via {@link AbstractLazyFluxReader} for efficient memory usage
 * - Auto-build functionality that compiles native extensions from bundled source code
 * - Cross-platform support for Windows, Linux, and macOS
 *
 * The reader implements a hybrid architecture combining Java configuration and control logic
 * with native C processing for optimal performance. The Java layer handles configuration,
 * property management, format detection, and framework integration, while the native layer
 * performs actual FST parsing using the official fstapi.c implementation.
 *
 * The native extension build process automatically extracts and compiles bundled C source code,
 * FST library components, and flux communication layer on first use. This ensures compatibility
 * across different platforms while maintaining the performance benefits of native execution.
 *
 * Configuration options support comprehensive FST processing scenarios including signal filtering
 * with include/exclude patterns, time range selection for partial loading, hierarchical signal
 * organization, and various lazy loading configurations for memory optimization.
 *
 * Copyright (c) 2013-2025 Thomas Haber
 * All rights reserved.
 */
@RegistryAnnotation(annotation = FstNativeReader.Annotation.class)
public class FstNativeReader extends AbstractLazyFluxReader {

    public static class Annotation extends AbstractLazyFluxReader.Annotation {

        public static final String id = "de.toem.impulse.reader.fstn";
        public static final String label = I18n.Serializer_FstNativeReader;
        public static final String description = I18n.Serializer_FstNativeReader_Description;
        public static final String helpURL = I18n.Serializer_FstNativeReader_HelpURL;
        public static final String defaultNamePattern = "\\.fst$,\\.FST$";
        public static final String formatType = "fst";
        public static final String certificate = "3ytrsbsdJyWolXWjQnWV1WuQ2jRzqc/w\nVhODxCEfY7ExnE2ylazpEwuuq2EVmdJT\nM0t9ItNTq6MU6uVBl8aJVVrYkwPSzJaF\n8pLaRwogyyh+xg5PnO9Swf5TBRC+c83A\nhd3pE6+fAjtp7nyYQxH2qT43rOqWgoJ1\nb2QUkd6fUXJuhJOQhRkOz3NCSRJdeUR1\ng40cbYoHtca6qNR5j6Z8aEYFZTi0bQZX\n+3NEdgmKAuNK76Mi2K/D/S1QVBvLduSQ\ne8J98GdyanOQWbuG6GjjJmHTfiJhhi9w\nwzHnPiR888COGghSBegN1w==\n";
    }

    // ========================================================================================================================
    // Constructors
    // ========================================================================================================================
    /**
     * Default constructor for the FstReader.
     */
    public FstNativeReader() {
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
    public FstNativeReader(ISerializerDescriptor descriptor, String contentName, String contentType, String cellType, String configuration,
            String[][] properties, InputStream in) {
        super(descriptor, configuration, properties, getPropertyModel(descriptor, null), in);
    }

    // ========================================================================================================================
    // Support Interface
    // ========================================================================================================================

    /**
     * Determines if this reader supports the specified functionality request.
     *
     * @param request
     *            An Integer identifying the functionality being queried
     * @param context
     *            Additional context for the request
     * @return true if the reader supports the requested functionality, false otherwise
     */
    public static boolean supports(Object request, Object context) {
        int ir = request instanceof Integer ? ((Integer) request).intValue() : -1;
        if (SUPPORT_CONFIGURATION == ir && DefaultSerializerConfiguration.TYPE.equals(context))
            return true;
        return ir == (ir & SUPPORT_PROPERTIES | SUPPORT_NATIVE_BUILD);
    }

    /**
     */
    public static boolean storeNative(File folder) {
        try {

            Utils.write(folder,"main.c", Bundles.getBundleEntryAsByteArray(FstNativeReader.class, "/src/de/toem/impulse/extension/eda/waveform/fst/main.c"));
            Utils.write(folder,"Makefile", Bundles.getBundleEntryAsByteArray(FstNativeReader.class, "/src/de/toem/impulse/extension/eda/waveform/fst/Makefile"));
            Utils.write(folder,"fstapi.zip", Bundles.getBundleEntryAsByteArray(FstNativeReader.class, "/src/fstapi.zip"));
            Utils.write(folder,"flux.zip", Bundles.getBundleEntryAsByteArray("de.toem.impulse.base", "/flux/flux_c.zip"));
            return true;
        } catch (Throwable e) {
        }
        return false;
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
        boolean notPref = context != IRegistryObject.Preference.class;
        PropertyModel model = IParsingRecordReader
                .getPropertyModel(PROP_EMPTY|PROP_INCLUDE | PROP_LAZY | PROP_HIERARCHY | (notPref ? (PROP_RANGE | PROP_TRANSFORM) : 0))
                .add(ConfiguredConsoleStream.getPropertyModel());
        if (context == IRegistryObject.Preference.class || context==null) {
            model.add(NativeExtensionBuilder.getPropertyModel(null,null));
        }
        return model;
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
    public int isApplicable(String name, String contentType, String cellType, IInputRequest inputRequest) {
        // Check if file has FST extension
        if (name != null && !name.toLowerCase().endsWith(".fst")) {
            return NOT_APPLICABLE;
        }
        // Check header block size
        byte[] header = inputRequest.bytes(9);
        byte[] expected = {   0, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x01, 0x49 };  // normal header
        byte[] expected2 = { -2, 0x00, 0x00};  // everthing packed
        return Utils.equals(header, expected) || Utils.equals(Arrays.copyOf(header, 3), expected2)  ? APPLICABLE : NOT_APPLICABLE;
    }

    // ========================================================================================================================
    // Native execution
    // ========================================================================================================================

    protected String getNativeName() {
        return "fst2flux";
    }
}