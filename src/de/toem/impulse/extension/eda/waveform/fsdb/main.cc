//package de.toem.impulse.extension.eda.waveform.fsdb
/*******************************************************************************
 *
 * Native FSDB (Fast Signal Database) to FLX (Flux) converter for the impulse framework.
 *
 * This native implementation provides high-performance conversion of FSDB waveform files
 * to the impulse FLX format through direct integration with the Synopsys Verdi FSDB API library.
 * It serves as the core processing engine for the FsdbNativeReader Java component, offering
 * superior parsing speed and memory efficiency compared to pure Java implementations.
 *
 * Key features of this implementation:
 * - Direct FSDB API integration for optimal parsing performance
 * - Complete support for Verilog and VHDL signal types and data encodings
 * - Time-based value change traversal with efficient incremental loading
 * - Hierarchical scope and signal reconstruction from FSDB metadata
 * - Lazy loading support via FLX control protocol for memory-efficient processing
 * - Configurable output compression (none, LZ4, dual-stage FLZ+LZ4)
 * - Cross-platform compatibility (Windows, Linux, macOS with Verdi installation)
 *
 * Architecture:
 * The converter operates in two modes:
 * 1. Eager mode: Full conversion of all signals and value changes in a single pass
 * 2. Lazy mode: Control protocol-based incremental loading of requested signals only
 *
 * In lazy mode, the converter:
 * - Exports the complete hierarchy and signal metadata upfront
 * - Waits for FLX control messages requesting specific signal subsets
 * - Loads and processes only the requested signals' value changes on demand
 * - Supports multiple request/response cycles for progressive loading
 * - Automatically unloads signals after processing to minimize memory usage
 *
 * Data flow:
 * FSDB file → FSDB API parsing → hierarchy extraction → FLX trace structure →
 * time-based value change traversal → FLX output buffer → compression layers → stdout
 *
 * Memory management:
 * - Dynamic allocation for trace structures and buffers based on detected signal counts
 * - Signal load/unload cycles to bound memory during lazy processing
 * - Optional compression layers to reduce output bandwidth
 *
 * Copyright (c) 2012-2025 Thomas Haber
 *
 *******************************************************************************/

#ifdef NOVAS_FSDB
#undef NOVAS_FSDB
#endif

#include "ffrAPI.h"
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <ctype.h>
#include <stdint.h>
#include <unistd.h>
#include <stdarg.h>
#include <errno.h>
#include <map>
#include <time.h>
#include "flx.h"

#ifdef _WIN32
#include <io.h>
#include <fcntl.h>
#endif

// geometry
#define MAX_ENTRY_SIZE 4096 * 16
#define MAX_TRACE_REQUEST_ITEMS (4096*2) // max no of items to request
#define VERSION 1

// trace object
ffrObject * fsdbObj;

flxTrace trace;
unsigned maxSignals;
unsigned maxScopes;
flxid currentScope;
flxdomain start;
flxdomain end;
flxdomain zero;

int64_t timespecDiff(struct timespec *timeA_p, struct timespec *timeB_p) {
	return ((timeA_p->tv_sec * 1000000000) + timeA_p->tv_nsec) - ((timeB_p->tv_sec * 1000000000) + timeB_p->tv_nsec);
}

// ######################################################################################################################
// Trace signals and scopes
// ######################################################################################################################

/**
 * Base function for adding scopes to the FLX trace hierarchy.
 *
 * Creates a nested scope entry in the trace structure, maintaining the hierarchical
 * relationship via the currentScope global. Each scope is assigned a unique ID by
 * combining the maxSignals base with the scope count offset.
 *
 * @param name Scope name string
 * @param description Scope type description (e.g., "module", "task", "struct")
 */
static void traceScopeBase(flxtext name, flxtext description) {
	flxid nextScope = maxSignals + maxScopes;
	flxAddScope(trace, nextScope, currentScope, name, description, NULL);
	currentScope = nextScope;
	maxScopes++;
}

static void traceStruct(fsdbTreeCBDataStructBegin* structure, void *user) {
	traceScopeBase(structure->name, "struct");
}
//static void traceArray(fsdbTreeCBDataArrayBegin* array, void *user) {
//}

static void traceScope(fsdbTreeCBDataScope* scope, void *user) {
	flxtext description;

	switch (scope->type) {
	case FSDB_ST_VCD_MODULE:
		description = (flxtext) "module";
		break;

	case FSDB_ST_VCD_TASK:
		description = (flxtext) "task";
		break;

	case FSDB_ST_VCD_FUNCTION:
		description = (flxtext) "function";
		break;

	case FSDB_ST_VCD_BEGIN:
		description = (flxtext) "begin";
		break;

	case FSDB_ST_VCD_FORK:
		description = (flxtext) "fork";
		break;

	case FSDB_ST_VHDL_ARCHITECTURE:
		description = (flxtext) "vhdl_architecture";
		break;

	case FSDB_ST_VHDL_PROCEDURE:
		description = (flxtext) "vhdl_procedure";
		break;

	case FSDB_ST_VHDL_FUNCTION:
		description = (flxtext) "vhdl_function";
		break;

	case FSDB_ST_VHDL_RECORD:
		description = (flxtext) "vhdl_record";
		break;

	case FSDB_ST_VHDL_PROCESS:
		description = (flxtext) "vhdl_process";
		break;

	case FSDB_ST_VHDL_BLOCK:
		description = (flxtext) "vhdl_block";
		break;

	case FSDB_ST_VHDL_FOR_GENERATE:
		description = (flxtext) "vhdl_for_generate";
		break;

	case FSDB_ST_VHDL_IF_GENERATE:
		description = (flxtext) "vhdl_if_generate";
		break;

	default:
		description = (flxtext) "unknown_scope_type";
		break;
	}

	traceScopeBase(scope->name, description);
}

static void traceVar(fsdbTreeCBDataVar *var, void *user) {

	flxtext description;
	flxbyte type = FLX_DATA_TYPE_LOGIC;

	switch (var->type) {
	case FSDB_VT_VCD_EVENT:
		description = (flxtext) "event";
		break;

	case FSDB_VT_VCD_INTEGER:
		description = (flxtext) "integer";
		break;

	case FSDB_VT_VCD_PARAMETER:
		description = (flxtext) "parameter";
		break;

	case FSDB_VT_VCD_REAL:
		type = FLX_DATA_TYPE_FLOAT;
		description = (flxtext) "real";
		break;

	case FSDB_VT_VCD_REG:
		description = (flxtext) "reg";
		break;

	case FSDB_VT_VCD_SUPPLY0:
		description = (flxtext) "supply0";
		break;

	case FSDB_VT_VCD_SUPPLY1:
		description = (flxtext) "supply1";
		break;

	case FSDB_VT_VCD_TIME:
		description = (flxtext) "time";
		break;

	case FSDB_VT_VCD_TRI:
		description = (flxtext) "tri";
		break;

	case FSDB_VT_VCD_TRIAND:
		description = (flxtext) "triand";
		break;

	case FSDB_VT_VCD_TRIOR:
		description = (flxtext) "trior";
		break;

	case FSDB_VT_VCD_TRIREG:
		description = (flxtext) "trireg";
		break;

	case FSDB_VT_VCD_TRI0:
		description = (flxtext) "tri0";
		break;

	case FSDB_VT_VCD_TRI1:
		description = (flxtext) "tri1";
		break;

	case FSDB_VT_VCD_WAND:
		description = (flxtext) "wand";
		break;

	case FSDB_VT_VCD_WIRE:
		description = (flxtext) "wire";
		break;

	case FSDB_VT_VCD_WOR:
		description = (flxtext) "wor";
		break;

	case FSDB_VT_VCD_MEMORY:
		description = (flxtext) "vcd_memory";
		break;

	case FSDB_VT_VCD_MEMORY_DEPTH:
		description = (flxtext) "vcd_memory_depth_or_range";
		break;

	case FSDB_VT_VHDL_SIGNAL:
		description = (flxtext) "vhdl_signal";
		break;

	case FSDB_VT_VHDL_VARIABLE:
		description = (flxtext) "vhdl_variable";
		break;

	case FSDB_VT_VHDL_CONSTANT:
		description = (flxtext) "vhdl_constant";
		break;

	case FSDB_VT_VHDL_FILE:
		description = (flxtext) "vhdl_file";
		break;

	case FSDB_VT_VHDL_MEMORY:
		description = (flxtext) "vhdl_memory";
		break;

	case FSDB_VT_VHDL_MEMORY_DEPTH:
		description = (flxtext) "vhdl_memory_depth";
		break;

	default:
		description = (flxtext) "unknown_var_type";
		break;
	}

	switch (var->vc_dt) {
	case FSDB_VC_DT_BYTE:
	case FSDB_VC_DT_SHORT:
	case FSDB_VC_DT_INT:
	case FSDB_VC_DT_LONG:
		type = FLX_DATA_TYPE_INTEGER;
		break;
	case FSDB_VC_DT_FLOAT:
	case FSDB_VC_DT_DOUBLE:
		type = FLX_DATA_TYPE_FLOAT;
		break;
	}

	// bit num from fsdb
	int scale = 0, from = 0, to = -1;
	if (var->lbitnum >= var->rbitnum) {
		scale = var->lbitnum - var->rbitnum + 1;
	} else {
		scale = var->rbitnum - var->lbitnum + 1;
	}

	// bit from/to from name
	unsigned nameLength = strlen(var->name);
	char varname[nameLength];
	strncpy(varname, var->name, nameLength);
	varname[nameLength] = 0;

	flxtext pos, posa = 0, posb, posc;
	pos = (char*) varname;
	while ((pos = strchr(pos + 1, '[')) != 0)
		posa = pos; // last [
	if (posa) {
		posb = strchr(posa, ']');
		posc = strchr(posa, ':');
	}
	if (posa && posb) {
		from = to = atoi(posa + 1);
		if (posc)
			from = atoi(posc + 1);
	}

	//char txt2[32];
	//sprintf(txt2, "l%ur%u<bits=%u>",var->lbitnum,var->rbitnum, scale);
	//description = txt2;

	// add item
	flxid itemId = var->u.idcode;
	if (type == FLX_DATA_TYPE_LOGIC && to >= from && (to + 1 - from) == scale) {
		// strip [..] & trim
		((char*) posa)[0] = 0;
		while (strlen(varname) > 0 && varname[strlen(varname) - 1] == ' ')
			varname[strlen(varname) - 1] = 0;

		// scattered
		if (flxAddScatteredSignal(trace, itemId, currentScope, varname, description, NULL, type, -1, NULL, from,
				to) == FLX_ERROR_ITEM_ALLREADY_DEFINED) {
			flxAddScatteredSignalReference(trace, itemId, currentScope, varname, description, NULL, from, to);
		}
	} else {

		if (flxAddSignal(trace, itemId, currentScope, varname, description, NULL, type, scale, NULL) == FLX_ERROR_ITEM_ALLREADY_DEFINED) {
			flxAddSignalReference(trace, itemId, currentScope, varname, description, NULL);
		}
	}

	// remember signal type
	trace->items[itemId - 1].signalType = type;
	trace->items[itemId - 1].signalScale = scale;
}

/**
 * FSDB hierarchy tree traversal callback function.
 *
 * Processes all hierarchy events during tree traversal including scope begin/end,
 * struct begin/end, array begin/end, and variable definitions. Maintains the
 * currentScope context to correctly nest signals within their containing scopes.
 *
 * Callback types handled:
 * - SCOPE: Verilog/VHDL scope definitions
 * - STRUCT_BEGIN/END: SystemVerilog struct boundaries
 * - ARRAY_BEGIN/END: Array definition boundaries (currently ignored)
 * - VAR: Signal/variable definitions
 * - UPSCOPE: Scope close (returns to parent scope)
 *
 * @param cbType Callback type identifying the hierarchy event
 * @param clientData Client data pointer (unused)
 * @param treeCbData Callback-specific data structure
 * @return TRUE to continue traversal, FALSE to abort
 */
static bool_T traceTreeItem(fsdbTreeCBType cbType, void *clientData, void *treeCbData) {
	switch (cbType) {
	case FSDB_TREE_CBT_BEGIN_TREE:
		break;

	case FSDB_TREE_CBT_SCOPE:
		traceScope((fsdbTreeCBDataScope *) treeCbData, 0);
		break;

	case FSDB_TREE_CBT_STRUCT_BEGIN:
		traceStruct((fsdbTreeCBDataStructBegin *) treeCbData, 0);
		break;

	case FSDB_TREE_CBT_ARRAY_BEGIN:
		//traceArray((fsdbTreeCBDataArrayBegin *) treeCbData, 0);
		break;

	case FSDB_TREE_CBT_VAR:
		traceVar((fsdbTreeCBDataVar *) treeCbData, 0);
		break;

	case FSDB_TREE_CBT_UPSCOPE:
	case FSDB_TREE_CBT_STRUCT_END:
		currentScope = trace->items[currentScope - 1].parentId;
		break;

	case FSDB_TREE_CBT_END_TREE:
	case FSDB_TREE_CBT_ARRAY_END:
		break;

	case FSDB_TREE_CBT_FILE_TYPE:
	case FSDB_TREE_CBT_SIMULATOR_VERSION:
	case FSDB_TREE_CBT_SIMULATION_DATE:
	case FSDB_TREE_CBT_X_AXIS_SCALE:
	case FSDB_TREE_CBT_END_ALL_TREE:
	case FSDB_TREE_CBT_RECORD_BEGIN:
	case FSDB_TREE_CBT_RECORD_END:
		break;

	default:
		return 0;
	}
	return 1;
}

/**
 * Scope counting callback for geometry detection.
 *
 * Simple callback that counts scope and struct definitions during an initial
 * hierarchy traversal to determine memory requirements before full processing.
 *
 * @param cbType Callback type identifying the hierarchy event
 * @param clientData Client data pointer (unused)
 * @param treeCbData Callback-specific data structure
 * @return Always TRUE to continue traversal
 */
static bool_T scopeCountCallback(fsdbTreeCBType cbType, void *clientData, void *treeCbData) {
	switch (cbType) {
	case FSDB_TREE_CBT_SCOPE:
	case FSDB_TREE_CBT_STRUCT_BEGIN:
		//case FSDB_TREE_CBT_ARRAY_BEGIN:
		maxScopes++;
		break;
	}
	return 1;
}

// ######################################################################################################################
// Trace value changes
// ######################################################################################################################

/**
 * Processes a value change event from FSDB and writes it to the FLX trace.
 *
 * Comprehensive value change handling for multiple data types and encodings:
 *
 * Verilog (VCD) encoding:
 * - 1 byte per bit: Maps VCD states (0, 1, X, Z) to FLX logic states
 * - 2 bytes per bit: Reserved for extended encodings (not yet implemented)
 * - 4 bytes per bit: Float values or memory depth markers
 * - 8 bytes per bit: Double/real values
 *
 * VHDL encoding:
 * - 1 byte per bit: Maps 9-state VHDL std_ulogic (U, X, 0, 1, Z, W, L, H, -) to FLX states
 * - Multi-byte variants similar to Verilog
 *
 * Conflict detection:
 * Logic values containing X, U, or unknown states are marked with conflict flag
 * for proper rendering in waveform viewers.
 *
 * @param itemId Signal handle/ID identifying which signal changed
 * @param vcTrvsHdl Time-based value change traversal handle providing access to FSDB data
 * @param vc Pointer to the raw value change byte array
 * @param time Absolute timestamp for this value change
 */
static void traceValueChange(int itemId, /*ffrVCTrvsHdl*/
ffrTimeBasedVCTrvsHdl vcTrvsHdl, byte_T *vc, flxdomain time) {
	unsigned n;

	fsdbVarType varType = vcTrvsHdl->ffrGetVarType();
	int bs;
	static flxbyte buffer[FSDB_MAX_BIT_SIZE + 1 + 32];
	flxbptr bufferp = buffer;
	int conflict = 0;
	flxbyte type = trace->items[itemId - 1].signalType;
	flxuint scale = trace->items[itemId - 1].signalScale;

	// VERILOG
	if (varType >= FSDB_VT_VCD_EVENT && varType <= FSDB_VT_VCD_REG2) {

		switch (vcTrvsHdl->ffrGetBytesPerBit()) {
		case FSDB_BYTES_PER_BIT_1B:
			bs = vcTrvsHdl->ffrGetBitSize();
			for (n = 0; n < bs; n++) {
				switch (vc[n]) {
				case FSDB_BT_VCD_0:
					bufferp[n] = FLX_STATE_0_BITS;
					break;

				case FSDB_BT_VCD_1:
					bufferp[n] = FLX_STATE_1_BITS;
					break;

				case FSDB_BT_VCD_X:
					bufferp[n] = FLX_STATE_X_BITS;
					conflict = 1;
					break;

				case FSDB_BT_VCD_Z:
					bufferp[n] = FLX_STATE_Z_BITS;
					break;

				default:
					bufferp[n] = FLX_STATE_X_BITS;
					conflict = 1;
					break;
				}
			}
			flxWriteLogicStatesAt(trace, itemId, conflict, time, 0, scale > bs || bs < 1 ? FLX_STATE_0_BITS : buffer[0],
					buffer, bs,scale);
			break;

		case FSDB_BYTES_PER_BIT_2B:

			break;

		case FSDB_BYTES_PER_BIT_4B:

			switch (varType) {
			case FSDB_VT_VCD_MEMORY_DEPTH:
			case FSDB_VT_VHDL_MEMORY_DEPTH:
				break;

			default:
				flxWriteFloatAt(trace, itemId, 0, time, 0, vc, 4);
				break;
			}
			break;

		case FSDB_BYTES_PER_BIT_8B:
			varType = vcTrvsHdl->ffrGetVarType();
			switch (varType) {
			case FSDB_VT_VCD_REAL:
				flxWriteFloatAt(trace, itemId, 0, time, 0, vc, 4);
				break;

			case FSDB_VT_STREAM:
			default:
				break;
			}
			break;

		}
	} else
	// VHDL
	{
		switch (vcTrvsHdl->ffrGetBytesPerBit()) {
		case FSDB_BYTES_PER_BIT_1B:
			bs = vcTrvsHdl->ffrGetBitSize();
			for (n = 0; n < bs; n++) {
				switch (vc[n]) {
				case FSDB_BT_VHDL_STD_ULOGIC_U:
					bufferp[n] = FLX_STATE_U_BITS;

					//conflict = 1; // remove this
					//bufferp[n] = FLX_STATE_X_BITS;
					break;

				case FSDB_BT_VHDL_STD_ULOGIC_X:
					conflict = 1;
					bufferp[n] = FLX_STATE_X_BITS;
					break;

				case FSDB_BT_VHDL_STD_ULOGIC_0:
					bufferp[n] = FLX_STATE_0_BITS;
					break;

				case FSDB_BT_VHDL_STD_ULOGIC_1:
					bufferp[n] = FLX_STATE_1_BITS;
					break;

				case FSDB_BT_VHDL_STD_ULOGIC_Z:
					bufferp[n] = FLX_STATE_Z_BITS;
					break;

				case FSDB_BT_VHDL_STD_ULOGIC_W:
					bufferp[n] = FLX_STATE_W_BITS;
					break;

				case FSDB_BT_VHDL_STD_ULOGIC_L:
					bufferp[n] = FLX_STATE_L_BITS;
					break;

				case FSDB_BT_VHDL_STD_ULOGIC_H:
					bufferp[n] = FLX_STATE_H_BITS;
					break;

				case FSDB_BT_VHDL_STD_ULOGIC_DASH:
					bufferp[n] = FLX_STATE_D_BITS;
					break;

				default:
					bufferp[n] = FLX_STATE_U_BITS;
					conflict = 1;
					break;
				}
			}
			flxWriteLogicStatesAt(trace, itemId, conflict, time, 0, scale > bs || bs < 1 ? FLX_STATE_0_BITS : buffer[0],
					buffer, bs,scale);
			break;

		case FSDB_BYTES_PER_BIT_2B:

			break;

		case FSDB_BYTES_PER_BIT_4B:

			switch (varType) {
			case FSDB_VT_VCD_MEMORY_DEPTH:
			case FSDB_VT_VHDL_MEMORY_DEPTH:
				break;

			default:
				flxWriteFloatAt(trace, itemId, 0, time, 0, vc, 4);
				break;
			}
			break;

		case FSDB_BYTES_PER_BIT_8B:
			varType = vcTrvsHdl->ffrGetVarType();
			switch (varType) {
			case FSDB_VT_VCD_REAL:
				flxWriteFloatAt(trace, itemId, 0, time, 0, vc, 4);
				break;

			case FSDB_VT_STREAM:
			default:
				break;
			}
			break;

		}

	}

	return;
}

// ######################################################################################################################
// Open/close trace
// ######################################################################################################################

/**
 * Initializes the FLX trace with FSDB timing metadata and domain configuration.
 *
 * Extracts timing information from the FSDB file (min/max time from fsdbTag64 structures)
 * and configures the FLX trace domain accordingly. Parses the FSDB scale unit string
 * to determine the appropriate domain base (e.g., "ns", "ps100", "us").
 *
 * The FSDB timescale format provides both digit multiplier and unit string, which
 * are combined to produce FLX domain base strings like "ps10" for 10 picoseconds.
 *
 * Sends the FLX open command with the configured domain and start time.
 */
void openTrace(){
	fsdbTag64 time;
	fsdbObj->ffrGetMinFsdbTag64(&time);
	start = (((uint64_t) time.H << 32) | ((uint64_t) time.L));
	fsdbObj->ffrGetMaxFsdbTag64(&time);
	end = (((uint64_t) time.H << 32) | ((uint64_t) time.L));
	zero = 0;

	// domain base
	char domainBase[16] = "s\0";
	uint_T digit;
	char *unit;
	fsdbRC rc = fsdbObj->ffrExtractScaleUnit(fsdbObj->ffrGetScaleUnit(), digit, unit);
	if (rc == FSDB_RC_SUCCESS) {
		sprintf(domainBase, "%s", unit);
		if (digit > 1)
			sprintf(domainBase + strlen(domainBase), "%u", digit);
	}

	// send open and close to notify about domain
	flxOpen(trace, 0, domainBase, start, 0);
}

/**
 * Finalizes the FLX trace by sending the close command with the end time.
 *
 * Marks the end of the trace data stream, allowing readers to know the complete
 * time range of the waveform. Should be called after all value changes have been
 * written to the trace.
 */
void closeTrace(){
	flxClose(trace, 0, end);
}

/**
 * Iterates through the complete FSDB hierarchy and populates the FLX trace structure.
 *
 * Performs a single-pass traversal of the FSDB hierarchy tree by setting the tree
 * callback function and invoking ffrReadScopeVarTree. The traceTreeItem callback
 * processes scope open/close entries and variable definitions, maintaining the
 * current scope context to correctly nest signals.
 *
 * This function must be called before any value change processing to establish
 * the complete signal and scope metadata in the trace structure.
 */
static void traceAllItems() {
	// read scopes and vars
	maxScopes = 1;
	currentScope = 0;
	fsdbObj->ffrSetTreeCBFunc(traceTreeItem, 0);
	fsdbObj->ffrReadScopeVarTree();
}

// ######################################################################################################################
// Control handler
// ######################################################################################################################

/**
 * Handles FLX control protocol requests for selective signal loading (lazy mode).
 *
 * Processes REQ_SIGNALS control messages containing a list of signal IDs to load.
 * Accumulates signal IDs across multiple messages (supporting continuation via moreToCome flag),
 * then performs selective signal loading and time-based value change traversal.
 *
 * FSDB-specific lazy loading process:
 * 1. Unload any previously loaded signals (ffrUnloadSignals)
 * 2. Reset signal list (ffrResetSignalList)
 * 3. Add requested signal IDs to load list (ffrAddToSignalList)
 * 4. Load selected signals (ffrLoadSignals)
 * 5. Create time-based traversal handle for loaded signals
 * 6. Iterate value changes and write to FLX trace
 * 7. Unload signals to free memory
 *
 * This enables memory-efficient lazy loading where only signals of interest are processed,
 * which is critical for large FSDB files with thousands of signals.
 *
 * @param command FLX control command type (e.g., HANDLE_FINISH_MESSAGE)
 * @param controlId Control ID identifying the command type
 * @param messageId Unique message ID for request/response correlation
 * @param memberId Parameter member ID within the control message
 * @param type Data type of the parameter
 * @param value Pointer to parameter value data
 * @param size Pointer to parameter size
 * @param opt Optional flags
 * @return FLX_OK on success, error code on failure
 */
flxresult handleReqSignals(flxbyte command, flxid controlId, flxid messageId, flxid memberId, flxbyte type, void **value,
		flxuint *size, flxuint *opt) {

	static flxuint itemIds[MAX_TRACE_REQUEST_ITEMS];
	static flxuint count = 0;
	flxbint pos = 0;
	flxuint val = 0;

// item ids as binary parameter
	FLX_CONTROL_HANDLE_BINARY_PARAMETER(0, bItemIds, MAX_ENTRY_SIZE)
	FLX_CONTROL_HANDLE_ENUM_PARAMETER(0, moreToCome, 0)

	if (command == FLX_CONTROL_HANDLE_FINISH_MESSAGE) {

		// extract itemIds
		while (pos < bItemIdsSize) {
			val = 0;
			pos += _plusread(&val, bItemIds + pos, bItemIds + bItemIdsSize);
			if (val != 0 && count < MAX_TRACE_REQUEST_ITEMS) {
				itemIds[count++] = val;
			}
		}
		if (moreToCome)
			return FLX_OK;

		fsdbTag64 time;
		byte_T *vc;
		fsdbVarIdcode sigArray[MAX_TRACE_REQUEST_ITEMS];
		fsdbVarIdcode currentId;
		int readableSignals = 0;

		// psoido open
		trace->open = FLX_ITEM_OPEN_LOCAL;
		trace->current = 0;

		// load all signals
		fsdbObj->ffrUnloadSignals();
		fsdbObj->ffrResetSignalList();
		for (pos = 0; pos < count; pos++) {
			fsdbObj->ffrAddToSignalList(itemIds[pos]);
		}
		fsdbObj->ffrLoadSignals();

		// traverse handler
		for (pos = 0; pos < count; pos++)
			if (flxIsSignal(trace, itemIds[pos]))
				sigArray[readableSignals++] = itemIds[pos];
		ffrTimeBasedVCTrvsHdl vcTrvsHdl = fsdbObj->ffrCreateTimeBasedVCTrvsHdl(readableSignals, sigArray);
		if (vcTrvsHdl && FSDB_RC_SUCCESS == vcTrvsHdl->ffrGetVC(&vc)) {
			vcTrvsHdl->ffrGetXTag((void*) &time);
			vcTrvsHdl->ffrGetVarIdcode(&currentId);
			traceValueChange(currentId, vcTrvsHdl, vc, (((uint64_t) time.H << 32) | ((uint64_t) time.L)));
		}
		while (vcTrvsHdl && FSDB_RC_SUCCESS == vcTrvsHdl->ffrGotoNextVC()) {
			vcTrvsHdl->ffrGetVC(&vc);
			vcTrvsHdl->ffrGetXTag((void*) &time);
			vcTrvsHdl->ffrGetVarIdcode(&currentId);
			traceValueChange(currentId, vcTrvsHdl, vc, (((uint64_t) time.H << 32) | ((uint64_t) time.L)));
		}

		// unload all signals
		fsdbObj->ffrUnloadSignals();

		// write result message & flush
		flxWriteControlResponse(trace, controlId, messageId, 0, 0);
		flxFlush(trace);

		// reset count
		count = 0;
	}
	return FLX_OK;
}


/**
 * Top-level FLX control command dispatcher.
 *
 * Routes incoming control commands to appropriate handlers based on controlId.
 * Currently supports DB_REQ_SIGNALS for lazy signal loading. Can be extended
 * to handle additional control protocols as needed.
 *
 * @param command FLX control command type
 * @param controlId Control ID identifying the command type
 * @param messageId Unique message ID for request/response correlation
 * @param memberId Parameter member ID within the control message
 * @param type Data type of the parameter
 * @param value Pointer to parameter value data
 * @param size Pointer to parameter size
 * @param opt Optional flags
 * @return FLX_OK on success, error code on failure
 */
flxresult handleCommands(flxbyte command, flxid controlId, flxid messageId, flxid memberId, flxbyte type, void **value,
		flxuint *size, flxuint *opt) {

	switch (controlId) {
	case FLX_CONTROL_DB_REQ_SIGNALS:
		return handleReqSignals(command, controlId, messageId, memberId, type, value, size, opt);
	}
	return FLX_ERROR_COMMAND_PARSE_ERROR;
}

// ######################################################################################################################
// Main
// ######################################################################################################################

/**
 * Prints command-line usage information to stdout.
 *
 * @param progname Program name to display in usage message (typically argv[0])
 */
static void print_usage(const char *progname) {
	const char *pn = progname ? progname : "fsdb2flx";
	fprintf(stdout,
		"Usage: %s [options] <fsdbfile>\n"
		"Options:\n"
		"  -h, --help        show this help message and exit\n"
		"  -l, --lazy        lazy loading (control mode)\n"
		"  -c, --compress N  compression level: 0=no, 1=LZ4, 2=FLZ+LZ4\n",
		pn);
}

/**
 * Main entry point for the FSDB to FLX converter.
 *
 * Command-line interface:
 * - Parses options for lazy mode (-l), compression level (-c), and help (-h)
 * - Validates FSDB file type and opens via FSDB API (ffrOpen3)
 * - Detects signal and scope counts for memory allocation via initial tree traversal
 * - Initializes FLX trace structure and output buffers with optional compression
 * - Exports hierarchy and metadata
 * - In eager mode: loads all signals and converts value changes in one pass
 * - In lazy mode: enters control protocol loop to process selective signal requests
 *
 * FSDB file validation:
 * - Checks file is valid FSDB format (ffrIsFSDB)
 * - Validates file type is Verilog, VHDL, or mixed (rejects other types)
 * - Reads data type definitions for transaction support (prevents crashes)
 *
 * Output:
 * Binary FLX format written to stdout. Use binary mode on Windows to prevent
 * newline translation corruption.
 *
 * Exit codes:
 * - 0: Success
 * - 1: Invalid arguments or compression level
 * - 2: Invalid FSDB info type
 * - 3: Could not open FSDB file
 * - 4: Invalid FSDB file type
 *
 * @param argc Argument count
 * @param argv Argument vector
 * @return Exit code
 */
int main(int argc, char **argv) {

	ffrFSDBInfo fsdbInfo;
	fsdbFileType fsdbFileType;
	unsigned fsdbBlkIdx = 0;
	int n;
	struct timespec t1, t2;
	clock_gettime(CLOCK_MONOTONIC, &t1);

#ifdef _WIN32
	setmode(fileno(stdout),O_BINARY);
	setmode(fileno(stdin),O_BINARY);
#endif

	// parse command line options
	int lazy = 0;
	int compressLevel = 0;
	const char *fsdbFilename = NULL;
	for (int i = 1; i < argc; ++i) {
		if (strcmp(argv[i], "-h") == 0 || strcmp(argv[i], "--help") == 0) {
			print_usage(argv[0]);
			return 0;
		} else if (strcmp(argv[i], "-l") == 0 || strcmp(argv[i], "--lazy") == 0) {
			lazy = 1;
		} else if (strcmp(argv[i], "-c") == 0 || strcmp(argv[i], "--compress") == 0) {
			if (i + 1 < argc) {
				compressLevel = atoi(argv[++i]);
			} else {
				fprintf(stderr, "Missing value for -c/--compress\n");
				print_usage(argv[0]);
				return 1;
			}
		} else if (strncmp(argv[i], "--compress=", 11) == 0) {
			compressLevel = atoi(argv[i] + 11);
		} else if (!fsdbFilename) {
			fsdbFilename = argv[i];
		} else {
			// ignore extra args
		}
	}

	if (compressLevel < 0 || compressLevel > 2) {
		fprintf(stderr, "Invalid compress level %d. Allowed values: 0, 1, 2\n", compressLevel);
		print_usage(argv[0]);
		return 1;
	}

	if (!fsdbFilename) {
		fprintf(stderr, "No fsdb file specified.\n");
		print_usage(argv[0]);
		return 1;
	}

// ######################################################################################################################
// open fsdb

	// check if output is fsdb
	if (!ffrObject::ffrIsFSDB((char*)fsdbFilename)) {
		fprintf(stderr, "Input is no FSDB file: %s \n", fsdbFilename);
		exit(1);
	}

	// check fsdb info type
	ffrObject::ffrGetFSDBInfo((char*)fsdbFilename, fsdbInfo);
	if ((fsdbInfo.file_type != FSDB_FT_VERILOG) && (fsdbInfo.file_type != FSDB_FT_VERILOG_VHDL)
			&& (fsdbInfo.file_type != FSDB_FT_VHDL)) {
		fprintf(stderr, "Invalid fsdb info type : %u \n", fsdbInfo.file_type);
		exit(2);
	}

	// open fsdb
	fsdbObj = ffrObject::ffrOpen3((char*)fsdbFilename);
	if (!fsdbObj) {
		fprintf(stderr, "Could not open file: %s \n", fsdbFilename);
		exit(3);
	}

	// check fsdb file type
	fsdbFileType = fsdbObj->ffrGetFileType();
	if ((fsdbFileType != FSDB_FT_VERILOG) && (fsdbFileType != FSDB_FT_VERILOG_VHDL) && (fsdbFileType != FSDB_FT_VHDL)) {
		fsdbObj->ffrClose();
		fprintf(stderr, "Invalid fsdb file type : %u \n", fsdbFileType);
		exit(4);
	}

	fsdbObj->ffrReadDataTypeDefByBlkIdx(fsdbBlkIdx); /* necessary if FSDB file has transaction data ... we don't process this but it prevents possible crashes */

// ######################################################################################################################
// detect fsdb geometry

	maxSignals = fsdbObj->ffrGetMaxVarIdcode();
	maxScopes = 1;
	fsdbObj->ffrSetTreeCBFunc(scopeCountCallback, 0);
	fsdbObj->ffrReadScopeVarTree();

// ######################################################################################################################
// trace & buffers

	// calculate required memory for trace and buffers
	unsigned bufferSize = FLX_BUFFER_BYTES(4096 * 16);
	unsigned traceSize = FLX_TRACE_BYTES(1, maxSignals + maxScopes);

	// trace memory
	unsigned char *memoryBuffer=(unsigned char *)malloc(bufferSize * 3);
	unsigned char *memoryTrace=(unsigned char *)malloc(traceSize);

	flxBuffer bufferOut = flxCreateSimpleBuffer(memoryBuffer + bufferSize * 2, bufferSize, flxWriteToFile, stdout);
	flxBuffer bufferLz4 = NULL;
	flxBuffer bufferFlz = NULL;
	flxBuffer topBuffer = bufferOut;

	if (compressLevel >= 1) {
		bufferLz4 = flxCreateSimpleBuffer(memoryBuffer + bufferSize * 1, bufferSize, flxCompressLz4, bufferOut);
		topBuffer = bufferLz4;
	}
	if (compressLevel >= 2) {
		bufferFlz = flxCreateSimpleBuffer(memoryBuffer + bufferSize * 0, bufferSize, flxCompressFlz, bufferLz4);
		topBuffer = bufferFlz;
	}

	trace = flxCreateTrace(0, maxSignals + maxScopes, MAX_ENTRY_SIZE, memoryTrace, traceSize, bufferOut);

// ######################################################################################################################
// send head and content

	flxAddHead(trace, "fsdb", NULL);
	flxFlush(trace);

	// active compression
	flxSetBuffer(trace, topBuffer);

	// trace all items
	traceAllItems();

	// open trace
	openTrace();

	//parse input
	if (lazy) {

		// close trace
		closeTrace();

		// send control scheme
		unsigned version = 1;
		unsigned maxTraceItems = MAX_TRACE_REQUEST_ITEMS;
		struct flxMemberValueStruct members[2];
		flxInitMember(members + 0, 0, NULL, NULL, NULL, NULL, FLX_DATA_TYPE_INTEGER, -1, NULL);
		flxInitMember(members + 1, 1, NULL, NULL, NULL, NULL, FLX_DATA_TYPE_INTEGER, -1, NULL);
		flxSetMember(members + 0, &version, sizeof(unsigned), 0, 1);
		flxSetMember(members + 1, &maxTraceItems, sizeof(unsigned), 0, 1);
		flxWriteControlRequest(trace, FLX_CONTROL_DB_SCHEME, 0, members, 2);
		flxFlush(trace);

		// enter control handler
		return flxParseControlInput(stdin, MAX_ENTRY_SIZE, handleCommands);

	} else {


		// load all signals
		fsdbObj->ffrUnloadSignals();
		fsdbObj->ffrResetSignalList();
		for (n = 1; n < maxSignals; n++) {
			if (flxIsSignal(trace, n))
				fsdbObj->ffrAddToSignalList(n);
		}
		fsdbObj->ffrLoadSignals();

		// traverse all signals
		fsdbVarIdcode sigArray[maxSignals];
		int readableSignals = 0;
		for (n = 1; n < maxSignals; n++)
			if (flxIsSignal(trace, n))
				sigArray[readableSignals++] = n;
		
		fsdbTag64 time;
		byte_T *vc;
		fsdbVarIdcode currentId;
		ffrTimeBasedVCTrvsHdl vcTrvsHdl = fsdbObj->ffrCreateTimeBasedVCTrvsHdl(readableSignals, sigArray);
		if (vcTrvsHdl && FSDB_RC_SUCCESS == vcTrvsHdl->ffrGetVC(&vc)) {
			vcTrvsHdl->ffrGetXTag((void*) &time);
			vcTrvsHdl->ffrGetVarIdcode(&currentId);
			traceValueChange(currentId, vcTrvsHdl, vc, (((uint64_t) time.H << 32) | ((uint64_t) time.L)));
		}
		while (vcTrvsHdl && FSDB_RC_SUCCESS == vcTrvsHdl->ffrGotoNextVC()) {
			vcTrvsHdl->ffrGetVC(&vc);
			vcTrvsHdl->ffrGetXTag((void*) &time);
			vcTrvsHdl->ffrGetVarIdcode(&currentId);
			traceValueChange(currentId, vcTrvsHdl, vc, (((uint64_t) time.H << 32) | ((uint64_t) time.L)));
		}

		// unload all signals
		fsdbObj->ffrUnloadSignals();

		closeTrace();
		flxFlush(trace);
	}
}
