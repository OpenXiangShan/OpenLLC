init:
	git submodule update --init
	cd rocket-chip && git submodule update --init hardfloat cde

test-top-l3:
	mill -i OpenLLC.test.runMain openLLC.TestTop_L3 -td build

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