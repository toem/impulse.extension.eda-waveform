#package de.toem.impulse.extension.eda.waveform.fst;
SOURCES?=main.c flux/flx.c flux/lz4.c flux/fastlz.c fstapi.c libz/adler32.c libz/compress.c libz/crc32.c libz/deflate.c libz/gzclose.c libz/gzlib.c libz/gzread.c libz/gzwrite.c libz/infback.c libz/inffast.c libz/inflate.c libz/inftrees.c libz/trees.c libz/uncompr.c libz/zutil.c
HEADER?=flux/flx.h  flux/lz4.h  flux/fastlz.h fstapi.h config.h
DEFINES?=-D FLX_CONTROL -D FLX_COMPRESS -D FLX_STDIO -D FLX_ITEM_EXTENSION="flxbyte signalType;flxuint signalScale;" -Wno-implicit-function-declaration
INCLUDES?=-Ilibz
LIBS?=
OUT=fst2flux
COMPILER?=gcc
RELEASE?=0
ADD_FLAGS?=
ADD_LIBS?=
PREBUILD?=1
FORCE?=0

ifeq ($(RELEASE),1)
FLAGS?=-I flux -I . $(INCLUDES) $(ADD_FLAGS) $(DEFINES) 
else
FLAGS?=-I flux -I . $(INCLUDES) $(ADD_FLAGS) $(DEFINES) -g
endif

ifeq ($(FORCE),1)
.FORCE:

DEPEND?=$(SOURCES) $(HEADER) Makefile .FORCE
else
DEPEND?=$(SOURCES) $(HEADER) Makefile
endif

######################################################################################
ifeq ($(OS),Windows_NT)
	
all: prepare-flux prepare-fstapi clean $(OUT).exe 

prepare-flux:
	@if not exist "flux" ( \
		echo Preparing flux folder from flux.zip... && \
		mkdir flux && \
		unzip -qo flux.zip -d flux || (echo Error: flux.zip not found or failed to extract && exit 1) \
	)

prepare-fstapi:
	@if not exist "fstapi.h" if not exist "fstapi.c" ( \
		echo Preparing fstapi files from fstapi.zip... && \
		unzip -qo fstapi.zip || (echo Error: fstapi.zip not found or failed to extract && exit 1) \
	)

$(OUT).exe: $(DEPEND)
	- mkdir bin
	$(COMPILER)  $(SOURCES) $(SOURCES_WIN32) $(FLAGS) $(FLAGS_WIN32) $(LIBS) $(LIBS_WIN32) $(ADD_LIBS) -o $(OUT).exe

clean:
	rm -rf $(OUT)	
	
else
UNAME_S := $(shell uname -s)

######################################################################################
ifeq ($(UNAME_S),Darwin)

all: prepare-flux prepare-fstapi $(OUT)

prepare-flux:
	@if [ ! -d "flux" ]; then \
		echo "Preparing flux folder from flux.zip..."; \
		mkdir -p flux && \
		unzip -qo flux.zip -d flux || (echo "Error: flux.zip not found or failed to extract" && exit 1); \
	fi

prepare-fstapi:
	@if [ ! -f "fstapi.h" ] || [ ! -f "fstapi.c" ]; then \
		echo "Preparing fstapi files from fstapi.zip..."; \
		unzip -qo fstapi.zip || (echo "Error: fstapi.zip not found or failed to extract" && exit 1); \
	fi

$(OUT): $(DEPEND)
	mkdir -p bin
	$(COMPILER) -m64 $(SOURCES) $(SOURCES_MACOSX) $(FLAGS) $(FLAGS_MACOSX) $(LIBS) $(LIBS_MACOSX) $(ADD_LIBS) -o $(OUT)
	chmod 777 $(OUT)
	
clean:
	rm -rf $(OUT)	

######################################################################################
else # Linux

all: prepare-flux prepare-fstapi $(OUT)

prepare-flux:
	@if [ ! -d "flux" ]; then \
		echo "Preparing flux folder from flux.zip..."; \
		mkdir -p flux && \
		unzip -qo flux.zip -d flux || (echo "Error: flux.zip not found or failed to extract" && exit 1); \
	fi

prepare-fstapi:
	@if [ ! -f "fstapi.h" ] || [ ! -f "fstapi.c" ]; then \
		echo "Preparing fstapi files from fstapi.zip..."; \
		unzip -qo fstapi.zip || (echo "Error: fstapi.zip not found or failed to extract" && exit 1); \
	fi

$(OUT): $(DEPEND)
	mkdir -p bin
	$(COMPILER)   $(SOURCES) $(SOURCES_LINUX) $(FLAGS) $(FLAGS_LINUX) $(LIBS) $(LIBS_LINUX) $(ADD_LIBS) -o $(OUT)
	chmod 777 $(OUT)

clean:
	rm -rf $(OUT)
	
endif
endif

######################################################################################



