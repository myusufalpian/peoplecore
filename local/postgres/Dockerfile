FROM postgres:18-alpine@sha256:77f585114c32fbca283dc835b0596f4e52b51b4c6662d7810b2f4084f60a1873
RUN apk add --no-cache 'zlib>=1.3.2-r1' 'nghttp2-libs>=1.70.0-r0' \
    'musl>=1.2.6-r3' 'musl-utils>=1.2.6-r3' 'brotli-libs>=1.2.1-r0' 'libcurl>=8.23.0-r0'
RUN apk add --no-cache su-exec \
    && rm /usr/local/bin/gosu \
    && sed -i 's/exec gosu /exec su-exec /g' /usr/local/bin/docker-entrypoint.sh
