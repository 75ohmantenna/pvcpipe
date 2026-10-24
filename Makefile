.PHONY: ci

ci:
	python3 -m unittest discover -s tools -p test_sanitize_recorded_mocks.py
	python3 tools/sanitize_recorded_mocks.py pvcpipe-extractor/extractor/src/test/resources/mocks
	./gradlew --no-daemon --no-continue :buildSrc:test :app:runCheckstyle :app:runKtlint \
		:app:checkDependenciesOrder :pvcpipe-extractor:extractor:checkstyleMain
	./gradlew --no-daemon --no-continue :app:testDebugUnitTest \
		:pvcpipe-extractor:extractor:forkCiTest
	./gradlew --no-daemon --no-continue :app:lintDebug
	./gradlew --no-daemon --no-continue :app:assembleDebug
	./gradlew --no-daemon --no-continue :app:assembleRelease
