# The jar is copied in rather than built here, and that is a deliberate split.
#
# Java bytecode is architecture-independent, so the jar built on an arm64 laptop is the
# same artifact that runs on this amd64 host. Building it inside the image would mean
# either cross-building under QEMU — slow, for no gain — or shipping a Gradle toolchain
# and the whole dependency set to the server. What travels is 80 MB of jar; what happens
# here is a copy into a runtime that already exists.
#
# The cost of that choice, stated plainly: this image is not reproducible from source on
# its own. It reproduces from `git tag + ./gradlew bootJar`, and the tag is the record.

FROM eclipse-temurin:21-jre-noble

# A numeric uid, not just a name. `--user` on the host side and the ownership of any
# mounted path are both numbers; a name that only exists inside the image cannot be
# matched against either.
RUN useradd --system --uid 10001 --no-create-home --shell /usr/sbin/nologin app

WORKDIR /app
COPY --chown=10001:10001 app.jar /app/app.jar

USER 10001

# MaxRAMPercentage rather than -Xmx: the JVM reads the cgroup limit that `--memory` sets,
# so the heap follows the container's ceiling instead of a number that has to be kept in
# sync with it by hand.
ENTRYPOINT ["java", \
	"-XX:MaxRAMPercentage=50", \
	"-XX:+ExitOnOutOfMemoryError", \
	"-jar", "/app/app.jar"]
