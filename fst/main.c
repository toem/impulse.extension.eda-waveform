//package de.toem.impulse.extension.eda.waveform.fst;
/*******************************************************************************
 * Copyright (c) 2012-2025 Thomas Haber
 *
 *
 *******************************************************************************/

#ifdef __cplusplus
extern "C"
{
#endif

#include <pthread.h> 
#include <stdio.h>
#include <string.h>
#include <math.h>
#include "flx.h"
#include "fstapi.h"
#include <stdlib.h> // added for exit, malloc

#ifdef _WIN32
#include <io.h>
#include <fcntl.h>
#endif

// geometry
#define MAX_ENTRY_SIZE 4096 * 16
#define MAX_TRACE_REQUEST_ITEMS (4096*2) // max no of items to request
#define VERSION 1

// trace object
flxTrace trace;
void * fstObject;
unsigned maxSignals;
unsigned maxScopes;
flxid currentScope;
flxdomain start;
flxdomain end;
flxdomain zero;

// ######################################################################################################################
// trace signals and scopes
// ######################################################################################################################

static const char *modtypes[] = { "module", "task", "function", "begin", "fork", "generate", "struct", "union", "class",
		"interface", "package", "program", "vhdl_architecture", "vhdl_procedure", "vhdl_function", "vhdl_record",
		"vhdl_process", "vhdl_block", "vhdl_for_generate", "vhdl_if_generate", "vhdl_generate", "vhdl_package" };

static void traceScope(struct fstHierScope* scope) {

	// scope type
	flxtext description = 0;
	//if (scope->typ >= FST_ST_MIN && scope->typ <= FST_ST_MAX)
	//	description = modtypes[scope->typ];

	flxid nextScope = maxSignals + maxScopes;
	flxAddScope(trace, nextScope, currentScope, scope->name, description, NULL);
	currentScope = nextScope;
	maxScopes++;
}

static const char *vartypes[] = { "event", "integer", "parameter", "real", "real_parameter", "reg", "supply0",
		"supply1", "time", "tri", "triand", "trior", "trireg", "tri0", "tri1", "wand", "wire", "wor", "port", "sparray",
		"realtime", "string", "bit", "logic", "int", "shortint", "longint", "byte", "enum", "shortreal" };

static void traceVar(struct fstHierVar* var) {
	flxtext description = 0;
	if (var->typ >= FST_VT_MIN && var->typ <= FST_VT_MAX)
		description = vartypes[var->typ];

	// bit num from fsdb
	int scale = var->length, from = 0, to = -1;

	flxbyte type = FLX_DATA_TYPE_LOGIC;
	switch (var->typ) {
	case FST_VT_VCD_REAL:
	case FST_VT_VCD_REAL_PARAMETER:
		type = FLX_DATA_TYPE_FLOAT;
		scale = -1;
		break;
	case FST_VT_GEN_STRING:
		type = FLX_DATA_TYPE_TEXT;
		scale = -1;
		break;
	}



	// bit from/to from name
	unsigned nameLength = var->name_length;
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

	// add item
	flxid itemId = var->handle;
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

static void traceAllItems() {

		// read scopes and vars
		maxScopes = 1;
		currentScope = 0;
		struct fstHier *h;
		fstReaderIterateHierRewind(fstObject);
		while ((h = fstReaderIterateHier(fstObject))) {

			switch (h->htyp) {
			case FST_HT_SCOPE:
				traceScope(&h->u.scope);
				break;
			case FST_HT_UPSCOPE:
				currentScope = trace->items[currentScope - 1].parentId;
				break;
			case FST_HT_VAR:
				traceVar(&h->u.var);
				break;
			}
		}
}

// ######################################################################################################################
// open/close trace
// ######################################################################################################################

void openTrace(){
		
		start = fstReaderGetStartTime(fstObject);
		zero = fstReaderGetTimezero(fstObject);		
		end = fstReaderGetEndTime(fstObject)+1;
		start += zero;
		end += zero;
		flxtext domainBase = "ns";
		signed char timescale = fstReaderGetTimescale(fstObject);
		switch (timescale) {
		case 2:
			domainBase = "s100";
			break;
		case 1:
			domainBase = "s10";
			break;
		case 0:
			domainBase = "s";
			break;

		case -1:
			domainBase = "ms100";
			break;
		case -2:
			domainBase = "ms10";
			break;
		case -3:
			domainBase = "ms";
			break;

		case -4:
			domainBase = "us100";
			break;
		case -5:
			domainBase = "us10";
			break;
		case -6:
			domainBase = "us";
			break;

		case -7:
			domainBase = "ns100";
			break;
		case -8:
			domainBase = "ns10";
			break;
		case -9:
			domainBase = "ns";
			break;

		case -10:
			domainBase = "ps100";
			break;
		case -11:
			domainBase = "ps10";
			break;
		case -12:
			domainBase = "ps";
			break;

		case -13:
			domainBase = "fs100";
			break;
		case -14:
			domainBase = "fs10";
			break;
		case -15:
			domainBase = "fs";
			break;

		case -16:
			domainBase = "as100";
			break;
		case -17:
			domainBase = "as10";
			break;
		case -18:
			domainBase = "as";
			break;
		}

		// send open and close to notify about domain
		flxOpen(trace, 0, domainBase, start, 0);
}

void closeTrace(){
		flxClose(trace, 0, end);
}

// ######################################################################################################################
// trace value changes
// ######################################################################################################################

void traceChangeVar(void *user_callback_data_pointer, uint64_t time, fstHandle itemId, const unsigned char *value,
		uint32_t len) {

	flxbyte type= trace->items[itemId - 1].signalType;
	flxuint scale = trace->items[itemId - 1].signalScale;
	time += zero;

	switch (type) {
	case FLX_DATA_TYPE_LOGIC: {
		int n, conflict = 0;
		for (n = 0; n < len; n++)
			if (value[n] == 'x' || value[n] == 'X') {
				conflict = 1;
				break;
			}
		flxWriteLogicTextAt(trace, itemId, conflict, time, 0, FLX_STATE_0_BITS , value, len, scale);
	}
		break;
	case FLX_DATA_TYPE_FLOAT: {
		double v = atof(value);
		flxWriteFloatAt(trace, itemId, 0, time, 0, &v, 8);
	}
		break;
	case FLX_DATA_TYPE_TEXT:
		flxWriteTextAt(trace, itemId, 0, time, 0, value, len);
		break;
	}

	//flxWriteLogicTextAt(trace, facidx, 0, time, 0, FLX_STATE_0_BITS, "11", 2);
}

void traceChange(void *user_callback_data_pointer, uint64_t time, fstHandle facidx, const unsigned char *value) {
	traceChangeVar(user_callback_data_pointer, time, facidx, value, strlen(value));
}

void traceAllChanges() {
		// iterate changes
		fstReaderSetFacProcessMaskAll(fstObject);
		fstReaderIterBlocks2(fstObject, traceChange, traceChangeVar, 0, 0);
}


// ######################################################################################################################
// control handler
// ######################################################################################################################


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

		// psoido open
		trace->open = FLX_ITEM_OPEN_LOCAL;
		trace->current = 0;

		// iterate changes
		fstReaderClrFacProcessMaskAll(fstObject);
		for (pos = 0; pos < count; pos++)
			if (flxIsSignal(trace, itemIds[pos]))
				fstReaderSetFacProcessMask(fstObject, itemIds[pos]);
		fstReaderIterBlocks2(fstObject, traceChange, traceChangeVar, 0, 0);

		// write result message & flush
		flxWriteControlResponse(trace, controlId, messageId, 0, 0);
		flxFlush(trace);

		// reset count
		count = 0;

	}
	return FLX_OK;
}

flxresult handleCommands(flxbyte command, flxid controlId, flxid messageId, flxid memberId, flxbyte type, void **value,
		flxuint *size, flxuint *opt) {

	switch (controlId) {
	case FLX_CONTROL_DB_REQ_SIGNALS:
		return handleReqSignals(command, controlId, messageId, memberId, type, value, size, opt);
	}
	return FLX_ERROR_COMMAND_PARSE_ERROR;
}

// ######################################################################################################################
// trace methods
// ######################################################################################################################


// ######################################################################################################################
// main
// ######################################################################################################################

// added: print usage helper
static void print_usage(const char *progname) {
    const char *pn = progname ? progname : "fst2flx";
    fprintf(stdout,
        "Usage: %s [options] <fstfile>\n"
        "Options:\n"
        "  -h, --help        show this help message and exit\n"
        "  -l, --lazy        lazy loading (control mode)\n"
        "  -c, --compress N  compression level: 0=no, 1=LZ4, 2=FLZ+LZ4\n",
        pn);
}

int main(int argc, char **argv) {

#ifdef _WIN32
	setmode(fileno(stdout),O_BINARY);
	setmode(fileno(stdin),O_BINARY);
#endif

	// parse command line options (new)
	int lazy = 0;
	int compressLevel = 0;
	const char *fstFilename = NULL;
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
		} else if (!fstFilename) {
			fstFilename = argv[i];
		} else {
			// ignore extra args
		}
	}

	if (compressLevel < 0 || compressLevel > 2) {
		fprintf(stderr, "Invalid compress level %d. Allowed values: 0, 1, 2\n", compressLevel);
		print_usage(argv[0]);
		return 1;
	}

	if (!fstFilename) {
		fprintf(stderr, "No fst file specified.\n");
		print_usage(argv[0]);
		return 1;
	}

	// ######################################################################################################################
	// open fst

	fstObject = fstReaderOpen((char*)fstFilename);
	if (!fstObject) {
		fprintf(stderr, "Could not open: %s \n", fstFilename);
		exit(20);
	}

	// ######################################################################################################################
	// detect geometry

	maxSignals = fstReaderGetMaxHandle(fstObject) + 1;
	maxScopes = 1;
	struct fstHier *h;
	while ((h = fstReaderIterateHier(fstObject))) {

		switch (h->htyp) {
		case FST_HT_SCOPE:
			maxScopes++;
			break;
		}
	}

	// ######################################################################################################################
	// trace & buffers


	// buffer & trace memory
	unsigned bufferSize = FLX_BUFFER_BYTES(4096 * 16);
	unsigned traceSize = FLX_TRACE_BYTES(1, maxSignals + maxScopes);
	unsigned char memoryBuffer[bufferSize * 3];
	unsigned char memoryTrace[traceSize];

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

	flxAddHead(trace, "fst", NULL);
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

	}else{

		traceAllChanges();
		closeTrace();
		flxFlush(trace);
	}

}

#ifdef __cplusplus
}
#endif

