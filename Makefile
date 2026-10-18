.PHONY: ci

ci:
	./gradlew --no-continue :buildSrc:test :app:runCheckstyle :app:runKtlint :app:checkDependenciesOrder \
		:app:lintDebug :app:testDebugUnitTest \
		:pvcpipe-extractor:extractor:forkCiTest
	./gradlew --no-continue :app:assembleDebug :app:assembleRelease
