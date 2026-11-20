# QA compiler only; never copied into the application image.
FROM gcr.io/distroless/java21-debian13:nonroot@sha256:d978eefd303c59c42f382aef811a0e41f2c68a6e5c319a23769cd3d1d5bf8e3c AS qa-certificates
FROM debian:trixie-slim@sha256:a72b4433de1cf7d6bae0c4cee74d0547f58601a643b3375f070c51600d63b6b2
ARG TARGETARCH
COPY --from=qa-certificates /etc/ssl/certs/ca-certificates.crt /etc/ssl/certs/ca-certificates.crt
RUN sed -i 's|http://deb.debian.org|https://deb.debian.org|g' /etc/apt/sources.list.d/debian.sources \
    && apt-get -o Acquire::Retries=3 -o Acquire::https::CaInfo=/etc/ssl/certs/ca-certificates.crt update \
    && apt-get -o Acquire::Retries=3 -o Acquire::https::CaInfo=/etc/ssl/certs/ca-certificates.crt install -y --no-install-recommends gcc g++ libc6-dev libexpat1-dev libfreetype-dev binutils \
    && rm -rf /var/lib/apt/lists/*
COPY scripts/security/native/*.c scripts/security/native/*.cpp /src/
COPY services/platform-api/runtime-guard.c /src/runtime-guard.c
RUN mkdir /probes \
    && gcc -Wall -Wextra -Werror -DXML_DTD /src/expat-regression.c -lexpat -o /probes/expat-regression \
    && gcc -Wall -Wextra -Werror -DXML_DTD -DXML_UNICODE /src/expat-regression.c -lexpatw -o /probes/expatw-regression \
    && gcc -Wall -Wextra -Werror /src/encoding-compatibility.c -lexpat -o /probes/encoding-compatibility \
    && gcc -Wall -Wextra -Werror -DXML_UNICODE /src/encoding-compatibility.c -lexpatw -o /probes/encodingw-compatibility \
    && gcc -Wall -Wextra -Werror -I/usr/include/freetype2 /src/freetype-compatibility.c -ldl -o /probes/freetype-compatibility \
    && gcc -Wall -Wextra -Werror /src/runtime-guard-tests.c -o /probes/runtime-guard-tests \
    && gcc -Wall -Wextra -Werror /src/runtime-environment.c -o /probes/runtime-environment \
    && gcc -Wall -Wextra -Werror /src/dns-search-regression.c -lresolv -o /probes/dns-search-regression \
    && set -- \
    && if [ "$TARGETARCH" = ppc64le ]; then set -- -Wno-psabi -mabi=ieeelongdouble; fi \
    && g++ -std=c++17 -Wall -Wextra -Werror "$@" /src/cxx-compatibility.cpp -pthread -o /probes/cxx-compatibility \
    && g++ -std=c++17 -Wall -Wextra -Werror "$@" -D_GLIBCXX_USE_CXX11_ABI=0 /src/cxx-compatibility.cpp -pthread -o /probes/cxx-compatibility-legacy \
    && if [ "$TARGETARCH" = ppc64le ]; then \
        g++ -std=c++17 -Wall -Wextra -Werror -Wno-psabi -mabi=ibmlongdouble /src/cxx-compatibility.cpp \
            -pthread -o /probes/cxx-compatibility-ibm-long-double \
        && readelf -VW /probes/cxx-compatibility | grep -q GLIBCXX_IEEE128_ \
        && readelf -VW /probes/cxx-compatibility-ibm-long-double | grep -q GLIBCXX_LDBL_; \
    fi
