FROM almalinux:8

RUN yum install -y \
            autoconf \
            automake \
            diffutils \
            gcc-c++ \
            glibc-devel \
            glibc-devel.i686 \
            java-11-openjdk-devel \
            libstdc++-devel \
            libstdc++-devel.i686 \
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

RUN curl -Ls https://github.com/Kitware/CMake/releases/download/v3.26.3/cmake-3.26.3-linux-x86_64.tar.gz | \
    tar xz -C /var/local    
    
# Install Maven
RUN curl -Ls https://archive.apache.org/dist/maven/maven-3/3.8.8/binaries/apache-maven-3.8.8-bin.tar.gz | \
    tar xz -C /var/local
ENV PATH /var/local/cmake-3.26.3-linux-x86_64/bin:/var/local/apache-maven-3.8.8/bin:$PATH

