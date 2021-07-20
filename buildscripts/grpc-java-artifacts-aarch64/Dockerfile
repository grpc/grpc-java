FROM dockcross/manylinux2014-aarch64

RUN yum install -y java-1.8.0-openjdk-devel && \
    yum clean all

# Install Maven
RUN curl -Ls http://apache.cs.utah.edu/maven/maven-3/3.3.9/binaries/apache-maven-3.3.9-bin.tar.gz | \
    tar xz -C /var/local
ENV PATH /var/local/apache-maven-3.3.9/bin:$PATH

# compiler/build.gradle expects to find binaries named "aarch64-linux-gnu-g++" and "aarch64-linux-gnu-objdump"
RUN ln -s /usr/xcc/aarch64-unknown-linux-gnueabi/bin/aarch64-unknown-linux-gnueabi-g++ /usr/local/bin/aarch64-linux-gnu-g++
RUN ln -s /usr/xcc/aarch64-unknown-linux-gnueabi/bin/aarch64-unknown-linux-gnueabi-objdump /usr/local/bin/aarch64-linux-gnu-objdump
