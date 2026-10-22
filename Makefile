.PHONY: ci

ci:
	./gradlew --no-daemon --no-continue :buildSrc:test :app:runCheckstyle :app:runKtlint \
		:app:checkDependenciesOrder :pvcpipe-extractor:extractor:checkstyleMain
	./gradlew --no-daemon --no-continue :app:testDebugUnitTest \
		:pvcpipe-extractor:extractor:forkCiTest
	./gradlew --no-daemon --no-continue :app:lintDebug
	./gradlew --no-daemon --no-continue :app:assembleDebug
	./gradlew --no-daemon --no-continue :app:assembleRelease
