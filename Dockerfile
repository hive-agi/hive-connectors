# The digest and release-roundup CLI (hive.connectors.digest.main) as an image,
# for Kubernetes CronJobs:
#
#   docker run --rm -e SLACK_BOT_TOKEN=... ghcr.io/hive-agi/hive-connectors:<tag> post
#   docker run --rm ... ghcr.io/hive-agi/hive-connectors:<tag> announce
#
# No uberjar: hive-build ships no uber task, and the CLI starts once a day, so
# the tools-deps runtime with every dependency fetched at build time is enough.
# The pod needs no Maven access at run time.
FROM clojure:temurin-21-tools-deps

RUN useradd --uid 1000 --create-home app
WORKDIR /app
RUN chown app:app /app
USER 1000:1000

# Dependencies first, so a source-only change does not re-resolve the world.
COPY --chown=1000:1000 deps.edn ./
RUN clojure -P -M:digest

COPY --chown=1000:1000 src ./src
COPY --chown=1000:1000 resources ./resources

# Pass --build-arg SOURCE_REF=$(git rev-parse HEAD) so the image names its commit.
ARG SOURCE_REF=unknown
LABEL org.opencontainers.image.revision=$SOURCE_REF \
      org.opencontainers.image.source=https://github.com/hive-agi/hive-connectors

# Compile once at build time, so the first run does not pay for it.
RUN clojure -M:digest status > /dev/null 2>&1 || true

ENTRYPOINT ["clojure", "-M:digest"]
CMD ["status"]
