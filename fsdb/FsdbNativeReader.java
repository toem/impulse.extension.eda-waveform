package de.toem.impulse.extension.eda.waveform.fsdb;

import java.io.File;
import java.io.InputStream;
import java.util.Arrays;

import de.toem.impulse.extension.eda.waveform.i18n.I18n;
import de.toem.impulse.serializer.AbstractLazyFluxReader;
import de.toem.impulse.serializer.IParsingRecordReader;
import de.toem.toolkits.core.Utils;
import de.toem.toolkits.pattern.bundles.Bundles;
import de.toem.toolkits.pattern.element.serializer.ISerializerDescriptor;
import de.toem.toolkits.pattern.element.serializer.SingletonSerializerPreference.DefaultSerializerConfiguration;
import de.toem.toolkits.pattern.ide.ConfiguredConsoleStream;
import de.toem.toolkits.pattern.properties.IPropertyModel;
import de.toem.toolkits.pattern.properties.PropertyModel;
import de.toem.toolkits.pattern.registry.IRegistryObject;
import de.toem.toolkits.pattern.registry.RegistryAnnotation;
import de.toem.toolkits.utils.natives.NativeExtensionBuilder;

/**
 * Native FSDB (Fast Signal Database) file reader implementation for the impulse framework.
 *
 * This reader provides high-performance FSDB file processing through a native C++ implementation
 * that leverages the Verdi FSDB API library. It offers superior parsing speed and memory efficiency
 * for processing large simulation datasets from Synopsys VCS and other FSDB-compatible simulators,
 * making it ideal for production verification workflows.
 *
 * Key features of this implementation include:
 * - Native performance through optimized C++ code with the official FSDB library
 * - Complete FSDB format support including compression, hierarchical structures, and variable types
 * - Lazy loading capabilities via {@link AbstractLazyFluxReader} for efficient memory usage
 * - Auto-build functionality that compiles native extensions from bundled source code
 * - Cross-platform support for Windows, Linux, and macOS
 *
 * The reader implements a hybrid architecture combining Java configuration and control logic
 * with native C++ processing for optimal performance. The Java layer handles configuration,
 * property management, format detection, and framework integration, while the native layer
 * performs actual FSDB parsing using the Verdi FSDB reader API.
 *
 * The native extension build process automatically extracts and compiles bundled C++ source code,
 * FSDB library components, and flux communication layer on first use. This ensures compatibility
 * across different platforms while maintaining the performance benefits of native execution.
 *
 * Configuration options support comprehensive FSDB processing scenarios including signal filtering
 * with include/exclude patterns, time range selection for partial loading, hierarchical signal
 * organization, and various lazy loading configurations for memory optimization.
 *
 * Copyright (c) 2012-2025 Thomas Haber
 * All rights reserved.
 */
@RegistryAnnotation(annotation = FsdbNativeReader.Annotation.class)
public class FsdbNativeReader extends AbstractLazyFluxReader {

    public static class Annotation extends AbstractLazyFluxReader.Annotation {

        public static final String id = "de.toem.impulse.reader.fsdbn";
        public static final String label = I18n.Serializer_FsdbNativeReader;
        public static final String description = I18n.Serializer_FsdbNativeReader_Description;
        public static final String helpURL = I18n.Serializer_FsdbNativeReader_HelpURL;
        public static final String defaultNamePattern = "\\.fsdb$,\\.FSDB$";
        public static final String formatType = "fsdb"; 
        public static final String certificate = "BfLKvaEjgZYzSnVc8/15JMlhKPfxhOTb\nVhODxCEfY7ExnE2ylazpEwuuq2EVmdJT\nV1fGhDxteqkU6uVBl8aJVVrYkwPSzJaF\nvAnUHwjPQsCv3KGeLiUNmWSdj9zty1AL\naHg/9FmzZZ7ARFbBSP0lFACA+1GrfCZE\ncG46Gdt81GjqvmfyOBPSTzFnkZYRgz97\nD6c6yZsd58Csi2BijgMuzMF52VT5XLhK\nTU3BsZxaOo+lZvsw+83GNneKmtbyriw6\n7gcTiIsOaJ5PJmmBC6o9YzagdeuEinID\n0TNfvYJ4Ar+m3Wp1lFVmjg==\n";
    }

    // ========================================================================================================================
    // Constructors
    // ========================================================================================================================
    /**
     * Default constructor for the FsdbReader.
     */
    public FsdbNativeReader() {
        super();
    }

    /**
     * Fully parameterized constructor for the FsdbReader.
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
    public FsdbNativeReader(ISerializerDescriptor descriptor, String contentName, String contentType, String cellType, String configuration,
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

            Utils.write(folder,"main.cc", Bundles.getBundleEntryAsByteArray(FsdbNativeReader.class, "/src/de/toem/impulse/extension/eda/waveform/fsdb/main.cc"));
            Utils.write(folder,"Makefile", Bundles.getBundleEntryAsByteArray(FsdbNativeReader.class, "/src/de/toem/impulse/extension/eda/waveform/fsdb/Makefile"));
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
            model.add(NativeExtensionBuilder.getPropertyModel(I18n.Serializer_FsdbNativeReader_LibDescription,I18n.Serializer_FsdbNativeReader_IncDescription));
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
        if (name != null && !name.toLowerCase().endsWith(".fsdb")) {
            return NOT_APPLICABLE;
        }
        return APPLICABLE;
        // Check header block size
//        byte[] header = inputRequest.bytes(9);
//        byte[] expected = {   0, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x01, 0x49 };  // normal header
//        byte[] expected2 = { -2, 0x00, 0x00};  // everthing packed
//        return Utils.equals(header, expected) || Utils.equals(Arrays.copyOf(header, 3), expected2)  ? APPLICABLE : NOT_APPLICABLE;
    }
    
    // ========================================================================================================================
    // Native execution
    // ========================================================================================================================
    
    protected String getNativeName() {
        return "fsdb2flux";
    }




}