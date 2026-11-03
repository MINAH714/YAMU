FROM eclipse-temurin:21-jdk-jammy

RUN apt-get update \
    && apt-get install -y --no-install-recommends ca-certificates curl unzip git python3 python3-venv \
    && rm -rf /var/lib/apt/lists/*

# Google command-line-tools archive, checksum published on developer.android.com/studio.
ARG SDK_ARCHIVE=commandlinetools-linux-15859902_latest.zip
ARG SDK_SHA256=4e4c464f145a7512b57d088ac6c278c03c9eea610886b35a5e0804e74eedf583
ENV ANDROID_HOME=/opt/android-sdk
ENV ANDROID_SDK_ROOT=/opt/android-sdk
ENV GRADLE_USER_HOME=/opt/gradle-cache
RUN curl --fail --location --retry 3 "https://dl.google.com/android/repository/${SDK_ARCHIVE}" -o /tmp/sdk.zip \
    && echo "${SDK_SHA256}  /tmp/sdk.zip" | sha256sum --check - \
    && mkdir -p /opt/android-sdk/cmdline-tools \
    && unzip -q /tmp/sdk.zip -d /opt/android-sdk/cmdline-tools \
    && mv /opt/android-sdk/cmdline-tools/cmdline-tools /opt/android-sdk/cmdline-tools/latest \
    && rm /tmp/sdk.zip
RUN python3 -c 'import subprocess; subprocess.run(["/opt/android-sdk/cmdline-tools/latest/bin/sdkmanager", "--sdk_root=/opt/android-sdk", "platform-tools", "platforms;android-37.0", "build-tools;36.0.0", "build-tools;37.0.0"], input=b"y\n"*100, check=True)'

COPY requirements.txt /tmp/publisher-requirements.txt
RUN python3 -m venv /opt/publisher \
    && /opt/publisher/bin/pip install --no-cache-dir -r /tmp/publisher-requirements.txt \
    && rm /tmp/publisher-requirements.txt
ENV PATH="/opt/publisher/bin:/opt/android-sdk/platform-tools:${PATH}"
WORKDIR /workspace
