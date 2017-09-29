Example project for Google App Engine
=====================================

This directory contains example projects demonstrating the use of gRPC in Google App Engine (GAE).
At this time, only gRPC clients are supported in GAE. gRPC servers are not supported in GAE.

Java 7 vs Java 8
================

The GAE Java 8 runtime has fewer restrictions than the Java 7 runtime, and supports the standard
public Java library. This allows the netty-transport to be used and allows servlets to have
long lived transport objects, rather than creating a new one for each servlet request.

In Java 7, the okhttp-transport must be used and each servlet request must create a new transport.

Prerequisite knowledge
===================

* [Quickstart for Java 8 for App Engine Standard Environment](https://cloud.google.com/appengine/docs/standard/java/quickstart-java8)
* [Quickstart for Java 7 for App Engine Standard Environment] https://cloud.google.com/appengine/docs/standard/java/quickstart
* [Using Gradle and the App Engine Plugin](https://cloud.google.com/appengine/docs/standard/java/tools/gradle)

Running the example locally
===========================

```bash
# cd into either the jdk7 or jdk8 example
# Make sure gcloud is in your $PATH
```

For Gradle:
```
$ ./gradlew appengineRun
```

For Maven:
```
mvn appengine:devserver
```

Point your browser to `http://localhost:8080/`, and verify the success message.

Running the example in GAE
==========================

```bash
# Log into Google Cloud
$ gcloud auth login

# Associate this codebase with a GAE project
$ gcloud config set project PROJECT_ID
```

For Gradle:
```
$ ./gradlew appengineDeploy
```

For Maven:
```
# Update appengine-web.xml and change YOUR-PROJECT-ID and YOUR-VERSION-ID

$ mvn appengine:update
```

Point your browser to the address printed in the gradle output, and verify the success message.

Tip: To run a specific deployment version, prepend the version identifier in the front of the
URL.