FROM centos:7.9.2009

RUN yum install -y \
            autoconf \
            automake \
            gcc-c++ \
            gcc-c++.i686 \
            glibc-devel \
            glibc-devel.i686 \
            java-11-openjdk-devel \
            libstdc++-devel \
            libstdc++-devel.i686 \
            libstdc++-static \
            libstdc++-static.i686 \
            libtool \
            make \
            tar \
            unzip \
            which \
            && \
    yum clean all

ENV ANDROID_HOME=/opt/Android/Sdk
RUN mkdir -p "$ANDROID_HOME/cmdline-tools" && \
    curl -Ls -o cmdline.zip \
        "https://dl.google.com/android/repository/commandlinetools-linux-9477386_latest.zip" && \
    unzip -qd "$ANDROID_HOME/cmdline-tools" cmdline.zip && \
    rm cmdline.zip && \
    mv "$ANDROID_HOME/cmdline-tools/cmdline-tools" "$ANDROID_HOME/cmdline-tools/latest" && \
    yes | "$ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager" --licenses

# Install Maven
RUN curl -Ls https://repo.maven.apache.org/maven2/org/apache/maven/apache-maven/3.3.9/apache-maven-3.3.9-bin.tar.gz | \
    tar xz -C /var/local
ENV PATH /var/local/apache-maven-3.3.9/bin:$PATH
