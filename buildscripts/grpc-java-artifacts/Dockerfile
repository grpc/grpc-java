FROM centos:7.9.2009

RUN yum install -y centos-release-scl && \
    yum install -y \
            devtoolset-7-gcc-c++ \
            glibc-devel \
            glibc-devel.i686 \
            java-1.8.0-openjdk-devel \
            libstdc++-devel \
            libstdc++-devel.i686 \
            libstdc++-static \
            libstdc++-static.i686 \
            libtool \
            make \
            tar \
            which \
            && \
    yum clean all

RUN curl -Ls https://github.com/Kitware/CMake/releases/download/v3.26.3/cmake-3.26.3-linux-x86_64.tar.gz | \
    tar xz -C /var/local
RUN curl -Ls http://dlcdn.apache.org/maven/maven-3/3.3.9/binaries/apache-maven-3.3.9-bin.tar.gz | \
    tar xz -C /var/local

ENV PATH /var/local/cmake-3.26.3-linux-x86_64/bin:/var/local/apache-maven-3.3.9/bin:$PATH
