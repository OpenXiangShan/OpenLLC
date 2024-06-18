init:
	git submodule update --init
	cd rocket-chip && git submodule update --init hardfloat cde
	cd coupledL2 && make init

compile:
	mill -i OpenLLC.compile

test-top-l3:
	mill -i OpenLLC.test.runMain openLLC.TestTop_L3 -td build

test-top-l2l3:
	mill -i OpenLLC.test.runMain openLLC.TestTopSoC_SingleCore -td build

test-top-l2l3l2:
	mill -i OpenLLC.test.runMain openLLC.TestTopSoC_DualCore -td build

clean:
	rm -rf ./build

bsp:
	mill -i mill.bsp.BSP/install

idea:
	mill -i mill.scalalib.GenIdea/idea

reformat:
	mill -i __.reformat

checkformat:
	mill -i __.checkFormat
