FROM centos:7.9.2009

RUN yum install -y \
            autoconf \
            automake \
            gcc-c++ \
            gcc-c++.i686 \
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

# Install Maven
RUN curl -Ls http://apache.cs.utah.edu/maven/maven-3/3.3.9/binaries/apache-maven-3.3.9-bin.tar.gz | \
    tar xz -C /var/local
ENV PATH /var/local/apache-maven-3.3.9/bin:$PATH
