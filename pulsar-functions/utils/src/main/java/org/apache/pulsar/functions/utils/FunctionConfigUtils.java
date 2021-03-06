/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.apache.pulsar.functions.utils;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import org.apache.commons.lang.StringUtils;
import org.apache.pulsar.common.functions.ConsumerConfig;
import org.apache.pulsar.common.functions.FunctionConfig;
import org.apache.pulsar.common.functions.Resources;
import org.apache.pulsar.common.functions.WindowConfig;
import org.apache.pulsar.common.naming.TopicName;
import org.apache.pulsar.functions.proto.Function;
import org.apache.pulsar.functions.proto.Function.FunctionDetails;

import java.io.File;
import java.lang.reflect.Type;
import java.net.MalformedURLException;
import java.util.*;

import static org.apache.commons.lang.StringUtils.isNotBlank;
import static org.apache.commons.lang.StringUtils.isNotEmpty;
import static org.apache.commons.lang3.StringUtils.isEmpty;
import static org.apache.pulsar.functions.utils.Utils.BUILTIN;
import static org.apache.pulsar.functions.utils.Utils.loadJar;

public class FunctionConfigUtils {
    public static FunctionDetails convert(FunctionConfig functionConfig, ClassLoader classLoader)
            throws IllegalArgumentException {

        Class<?>[] typeArgs = null;
        if (functionConfig.getRuntime() == FunctionConfig.Runtime.JAVA) {
            if (classLoader != null) {
                typeArgs = Utils.getFunctionTypes(functionConfig, classLoader);
            }
        }

        FunctionDetails.Builder functionDetailsBuilder = FunctionDetails.newBuilder();

        // Setup source
        Function.SourceSpec.Builder sourceSpecBuilder = Function.SourceSpec.newBuilder();
        if (functionConfig.getInputs() != null) {
            functionConfig.getInputs().forEach((topicName -> {
                sourceSpecBuilder.putInputSpecs(topicName,
                        Function.ConsumerSpec.newBuilder()
                                .setIsRegexPattern(false)
                                .build());
            }));
        }
        if (functionConfig.getTopicsPattern() != null && !functionConfig.getTopicsPattern().isEmpty()) {
            sourceSpecBuilder.putInputSpecs(functionConfig.getTopicsPattern(),
                    Function.ConsumerSpec.newBuilder()
                            .setIsRegexPattern(true)
                            .build());
        }
        if (functionConfig.getCustomSerdeInputs() != null) {
            functionConfig.getCustomSerdeInputs().forEach((topicName, serdeClassName) -> {
                sourceSpecBuilder.putInputSpecs(topicName,
                        Function.ConsumerSpec.newBuilder()
                                .setSerdeClassName(serdeClassName)
                                .setIsRegexPattern(false)
                                .build());
            });
        }
        if (functionConfig.getCustomSchemaInputs() != null) {
            functionConfig.getCustomSchemaInputs().forEach((topicName, schemaType) -> {
                sourceSpecBuilder.putInputSpecs(topicName,
                        Function.ConsumerSpec.newBuilder()
                                .setSchemaType(schemaType)
                                .setIsRegexPattern(false)
                                .build());
            });
        }
        if (functionConfig.getInputSpecs() != null) {
            functionConfig.getInputSpecs().forEach((topicName, consumerConf) -> {
                Function.ConsumerSpec.Builder bldr = Function.ConsumerSpec.newBuilder()
                        .setIsRegexPattern(consumerConf.isRegexPattern());
                if (!StringUtils.isBlank(consumerConf.getSchemaType())) {
                    bldr.setSchemaType(consumerConf.getSchemaType());
                } else if (!StringUtils.isBlank(consumerConf.getSerdeClassName())) {
                    bldr.setSerdeClassName(consumerConf.getSerdeClassName());
                }
                sourceSpecBuilder.putInputSpecs(topicName, bldr.build());
            });
        }

        // Set subscription type based on ordering and EFFECTIVELY_ONCE semantics
        Function.SubscriptionType subType = (functionConfig.isRetainOrdering()
                || FunctionConfig.ProcessingGuarantees.EFFECTIVELY_ONCE.equals(functionConfig.getProcessingGuarantees()))
                ? Function.SubscriptionType.FAILOVER
                : Function.SubscriptionType.SHARED;
        sourceSpecBuilder.setSubscriptionType(subType);

        if (isNotBlank(functionConfig.getSubName())) {
            sourceSpecBuilder.setSubscriptionName(functionConfig.getSubName());
        }

        if (typeArgs != null) {
            sourceSpecBuilder.setTypeClassName(typeArgs[0].getName());
        }
        if (functionConfig.getTimeoutMs() != null) {
            sourceSpecBuilder.setTimeoutMs(functionConfig.getTimeoutMs());
        }
        functionDetailsBuilder.setSource(sourceSpecBuilder);

        // Setup sink
        Function.SinkSpec.Builder sinkSpecBuilder = Function.SinkSpec.newBuilder();
        if (functionConfig.getOutput() != null) {
            sinkSpecBuilder.setTopic(functionConfig.getOutput());
        }
        if (!StringUtils.isBlank(functionConfig.getOutputSerdeClassName())) {
            sinkSpecBuilder.setSerDeClassName(functionConfig.getOutputSerdeClassName());
        }
        if (!StringUtils.isBlank(functionConfig.getOutputSchemaType())) {
            sinkSpecBuilder.setSchemaType(functionConfig.getOutputSchemaType());
        }

        if (typeArgs != null) {
            sinkSpecBuilder.setTypeClassName(typeArgs[1].getName());
        }
        functionDetailsBuilder.setSink(sinkSpecBuilder);

        if (functionConfig.getTenant() != null) {
            functionDetailsBuilder.setTenant(functionConfig.getTenant());
        }
        if (functionConfig.getNamespace() != null) {
            functionDetailsBuilder.setNamespace(functionConfig.getNamespace());
        }
        if (functionConfig.getName() != null) {
            functionDetailsBuilder.setName(functionConfig.getName());
        }
        if (functionConfig.getLogTopic() != null) {
            functionDetailsBuilder.setLogTopic(functionConfig.getLogTopic());
        }
        if (functionConfig.getRuntime() != null) {
            functionDetailsBuilder.setRuntime(Utils.convertRuntime(functionConfig.getRuntime()));
        }
        if (functionConfig.getProcessingGuarantees() != null) {
            functionDetailsBuilder.setProcessingGuarantees(
                    Utils.convertProcessingGuarantee(functionConfig.getProcessingGuarantees()));
        }

        if (functionConfig.getMaxMessageRetries() >= 0) {
            Function.RetryDetails.Builder retryBuilder = Function.RetryDetails.newBuilder();
            retryBuilder.setMaxMessageRetries(functionConfig.getMaxMessageRetries());
            if (isNotEmpty(functionConfig.getDeadLetterTopic())) {
                retryBuilder.setDeadLetterTopic(functionConfig.getDeadLetterTopic());
            }
            functionDetailsBuilder.setRetryDetails(retryBuilder);
        }

        Map<String, Object> configs = new HashMap<>();
        if (functionConfig.getUserConfig() != null) {
            configs.putAll(functionConfig.getUserConfig());
        }

        // windowing related
        WindowConfig windowConfig = functionConfig.getWindowConfig();
        if (windowConfig != null) {
            windowConfig.setActualWindowFunctionClassName(functionConfig.getClassName());
            configs.put(WindowConfig.WINDOW_CONFIG_KEY, windowConfig);
            // set class name to window function executor
            functionDetailsBuilder.setClassName("org.apache.pulsar.functions.windowing.WindowFunctionExecutor");

        } else {
            if (functionConfig.getClassName() != null) {
                functionDetailsBuilder.setClassName(functionConfig.getClassName());
            }
        }
        if (!configs.isEmpty()) {
            functionDetailsBuilder.setUserConfig(new Gson().toJson(configs));
        }

        functionDetailsBuilder.setAutoAck(functionConfig.isAutoAck());
        functionDetailsBuilder.setParallelism(functionConfig.getParallelism());
        if (functionConfig.getResources() != null) {
            Function.Resources.Builder bldr = Function.Resources.newBuilder();
            if (functionConfig.getResources().getCpu() != null) {
                bldr.setCpu(functionConfig.getResources().getCpu());
            }
            if (functionConfig.getResources().getRam() != null) {
                bldr.setRam(functionConfig.getResources().getRam());
            }
            if (functionConfig.getResources().getDisk() != null) {
                bldr.setDisk(functionConfig.getResources().getDisk());
            }
            functionDetailsBuilder.setResources(bldr.build());
        }
        return functionDetailsBuilder.build();
    }

    public static FunctionConfig convertFromDetails(FunctionDetails functionDetails) {
        FunctionConfig functionConfig = new FunctionConfig();
        functionConfig.setTenant(functionDetails.getTenant());
        functionConfig.setNamespace(functionDetails.getNamespace());
        functionConfig.setName(functionDetails.getName());
        functionConfig.setParallelism(functionDetails.getParallelism());
        functionConfig.setProcessingGuarantees(Utils.convertProcessingGuarantee(functionDetails.getProcessingGuarantees()));
        Map<String, ConsumerConfig> consumerConfigMap = new HashMap<>();
        for (Map.Entry<String, Function.ConsumerSpec> input : functionDetails.getSource().getInputSpecsMap().entrySet()) {
            ConsumerConfig consumerConfig = new ConsumerConfig();
            if (!isEmpty(input.getValue().getSerdeClassName())) {
                consumerConfig.setSerdeClassName(input.getValue().getSerdeClassName());
            }
            if (!isEmpty(input.getValue().getSchemaType())) {
                consumerConfig.setSchemaType(input.getValue().getSchemaType());
            }
            consumerConfig.setRegexPattern(input.getValue().getIsRegexPattern());
            consumerConfigMap.put(input.getKey(), consumerConfig);
        }
        functionConfig.setInputSpecs(consumerConfigMap);
        if (!isEmpty(functionDetails.getSource().getSubscriptionName())) {
            functionConfig.setSubName(functionDetails.getSource().getSubscriptionName());
        }
        if (functionDetails.getSource().getSubscriptionType() == Function.SubscriptionType.FAILOVER) {
            functionConfig.setRetainOrdering(true);
            functionConfig.setProcessingGuarantees(FunctionConfig.ProcessingGuarantees.EFFECTIVELY_ONCE);
        } else {
            functionConfig.setRetainOrdering(false);
            functionConfig.setProcessingGuarantees(FunctionConfig.ProcessingGuarantees.ATLEAST_ONCE);
        }
        functionConfig.setAutoAck(functionDetails.getAutoAck());
        functionConfig.setTimeoutMs(functionDetails.getSource().getTimeoutMs());
        if (!isEmpty(functionDetails.getSink().getTopic())) {
            functionConfig.setOutput(functionDetails.getSink().getTopic());
        }
        if (!isEmpty(functionDetails.getSink().getSerDeClassName())) {
            functionConfig.setOutputSerdeClassName(functionDetails.getSink().getSerDeClassName());
        }
        if (!isEmpty(functionDetails.getSink().getSchemaType())) {
            functionConfig.setOutputSchemaType(functionDetails.getSink().getSchemaType());
        }
        if (!isEmpty(functionDetails.getLogTopic())) {
            functionConfig.setLogTopic(functionDetails.getLogTopic());
        }
        functionConfig.setRuntime(Utils.convertRuntime(functionDetails.getRuntime()));
        functionConfig.setProcessingGuarantees(Utils.convertProcessingGuarantee(functionDetails.getProcessingGuarantees()));
        if (functionDetails.hasRetryDetails()) {
            functionConfig.setMaxMessageRetries(functionDetails.getRetryDetails().getMaxMessageRetries());
            if (!isEmpty(functionDetails.getRetryDetails().getDeadLetterTopic())) {
                functionConfig.setDeadLetterTopic(functionDetails.getRetryDetails().getDeadLetterTopic());
            }
        }
        Map<String, Object> userConfig;
        if (!isEmpty(functionDetails.getUserConfig())) {
            Type type = new TypeToken<Map<String, Object>>() {}.getType();
            userConfig = new Gson().fromJson(functionDetails.getUserConfig(), type);
        } else {
            userConfig = new HashMap<>();
        }
        if (userConfig.containsKey(WindowConfig.WINDOW_CONFIG_KEY)) {
            WindowConfig windowConfig = (WindowConfig) userConfig.get(WindowConfig.WINDOW_CONFIG_KEY);
            userConfig.remove(WindowConfig.WINDOW_CONFIG_KEY);
            functionConfig.setClassName(windowConfig.getActualWindowFunctionClassName());
            functionConfig.setWindowConfig(windowConfig);
        } else {
            functionConfig.setClassName(functionDetails.getClassName());
        }
        functionConfig.setUserConfig(userConfig);

        if (functionDetails.hasResources()) {
            Resources resources = new Resources();
            resources.setCpu(functionDetails.getResources().getCpu());
            resources.setRam(functionDetails.getResources().getRam());
            resources.setDisk(functionDetails.getResources().getDisk());
        }

        return functionConfig;
    }

    private static void doJavaChecks(FunctionConfig functionConfig, ClassLoader clsLoader) {
        Class<?>[] typeArgs = Utils.getFunctionTypes(functionConfig, clsLoader);
        // inputs use default schema, so there is no check needed there

        // Check if the Input serialization/deserialization class exists in jar or already loaded and that it
        // implements SerDe class
        if (functionConfig.getCustomSerdeInputs() != null) {
            functionConfig.getCustomSerdeInputs().forEach((topicName, inputSerializer) -> {
                ValidatorUtils.validateSerde(inputSerializer, typeArgs[0], clsLoader, true);
            });
        }

        // Check if the Input serialization/deserialization class exists in jar or already loaded and that it
        // implements SerDe class
        if (functionConfig.getCustomSchemaInputs() != null) {
            functionConfig.getCustomSchemaInputs().forEach((topicName, schemaType) -> {
                ValidatorUtils.validateSchema(schemaType, typeArgs[0], clsLoader, true);
            });
        }

        // Check if the Input serialization/deserialization class exists in jar or already loaded and that it
        // implements Schema or SerDe classes

        if (functionConfig.getInputSpecs() != null) {
            functionConfig.getInputSpecs().forEach((topicName, conf) -> {
                // Need to make sure that one and only one of schema/serde is set
                if (!isEmpty(conf.getSchemaType()) && !isEmpty(conf.getSerdeClassName())) {
                    throw new IllegalArgumentException(
                            String.format("Only one of schemaType or serdeClassName should be set in inputSpec"));
                }
                if (!isEmpty(conf.getSerdeClassName())) {
                    ValidatorUtils.validateSerde(conf.getSerdeClassName(), typeArgs[0], clsLoader, true);
                }
                if (!isEmpty(conf.getSchemaType())) {
                    ValidatorUtils.validateSchema(conf.getSchemaType(), typeArgs[0], clsLoader, true);
                }
            });
        }

        if (Void.class.equals(typeArgs[1])) {
            return;
        }

        // One and only one of outputSchemaType and outputSerdeClassName should be set
        if (!isEmpty(functionConfig.getOutputSerdeClassName()) && !isEmpty(functionConfig.getOutputSchemaType())) {
            throw new IllegalArgumentException(
                    String.format("Only one of outputSchemaType or outputSerdeClassName should be set"));
        }

        if (!isEmpty(functionConfig.getOutputSchemaType())) {
            ValidatorUtils.validateSchema(functionConfig.getOutputSchemaType(), typeArgs[1], clsLoader, false);
        }

        if (!isEmpty(functionConfig.getOutputSerdeClassName())) {
            ValidatorUtils.validateSerde(functionConfig.getOutputSerdeClassName(), typeArgs[1], clsLoader, false);
        }

    }

    private static void doPythonChecks(FunctionConfig functionConfig) {
        if (functionConfig.getProcessingGuarantees() == FunctionConfig.ProcessingGuarantees.EFFECTIVELY_ONCE) {
            throw new RuntimeException("Effectively-once processing guarantees not yet supported in Python");
        }

        if (functionConfig.getWindowConfig() != null) {
            throw new IllegalArgumentException("There is currently no support windowing in python");
        }

        if (functionConfig.getMaxMessageRetries() >= 0) {
            throw new IllegalArgumentException("Message retries not yet supported in python");
        }
    }

    private static void verifyNoTopicClash(Collection<String> inputTopics, String outputTopic) throws IllegalArgumentException {
        if (inputTopics.contains(outputTopic)) {
            throw new IllegalArgumentException(
                    String.format("Output topic %s is also being used as an input topic (topics must be one or the other)",
                            outputTopic));
        }
    }

    private static void doCommonChecks(FunctionConfig functionConfig) {
        if (isEmpty(functionConfig.getTenant())) {
            throw new IllegalArgumentException("Function tenant cannot be null");
        }
        if (isEmpty(functionConfig.getNamespace())) {
            throw new IllegalArgumentException("Function namespace cannot be null");
        }
        if (isEmpty(functionConfig.getName())) {
            throw new IllegalArgumentException("Function name cannot be null");
        }
        if (isEmpty(functionConfig.getClassName())) {
            throw new IllegalArgumentException("Function classname cannot be null");
        }

        Collection<String> allInputTopics = collectAllInputTopics(functionConfig);
        if (allInputTopics.isEmpty()) {
            throw new IllegalArgumentException("No input topic(s) specified for the function");
        }
        for (String topic : allInputTopics) {
            if (!TopicName.isValid(topic)) {
                throw new IllegalArgumentException(String.format("Input topic %s is invalid", topic));
            }
        }

        if (!isEmpty(functionConfig.getOutput())) {
            if (!TopicName.isValid(functionConfig.getOutput())) {
                throw new IllegalArgumentException(String.format("Output topic %s is invalid", functionConfig.getOutput()));
            }
        }

        if (!isEmpty(functionConfig.getLogTopic())) {
            if (!TopicName.isValid(functionConfig.getLogTopic())) {
                throw new IllegalArgumentException(String.format("LogTopic topic %s is invalid", functionConfig.getLogTopic()));
            }
        }

        if (!isEmpty(functionConfig.getDeadLetterTopic())) {
            if (!TopicName.isValid(functionConfig.getDeadLetterTopic())) {
                throw new IllegalArgumentException(String.format("DeadLetter topic %s is invalid", functionConfig.getDeadLetterTopic()));
            }
        }

        if (functionConfig.getParallelism() <= 0) {
            throw new IllegalArgumentException("Function parallelism should positive number");
        }
        // Ensure that topics aren't being used as both input and output
        verifyNoTopicClash(allInputTopics, functionConfig.getOutput());

        WindowConfig windowConfig = functionConfig.getWindowConfig();
        if (windowConfig != null) {
            // set auto ack to false since windowing framework is responsible
            // for acking and not the function framework
            if (functionConfig.isAutoAck() == true) {
                throw new IllegalArgumentException("Cannot enable auto ack when using windowing functionality");
            }
            WindowConfigUtils.validate(windowConfig);
        }

        if (functionConfig.getResources() != null) {
            ResourceConfigUtils.validate(functionConfig.getResources());
        }

        if (functionConfig.getTimeoutMs() != null && functionConfig.getTimeoutMs() <= 0) {
            throw new IllegalArgumentException("Function timeout must be a positive number");
        }

        if (functionConfig.getTimeoutMs() != null
                && functionConfig.getProcessingGuarantees() != null
                && functionConfig.getProcessingGuarantees() != FunctionConfig.ProcessingGuarantees.ATLEAST_ONCE) {
            throw new IllegalArgumentException("Message timeout can only be specified with processing guarantee is "
                    + FunctionConfig.ProcessingGuarantees.ATLEAST_ONCE.name());
        }

        if (functionConfig.getMaxMessageRetries() >= 0
                && functionConfig.getProcessingGuarantees() == FunctionConfig.ProcessingGuarantees.EFFECTIVELY_ONCE) {
            throw new IllegalArgumentException("MaxMessageRetries and Effectively once don't gel well");
        }
        if (functionConfig.getMaxMessageRetries() < 0 && !org.apache.commons.lang3.StringUtils.isEmpty(functionConfig.getDeadLetterTopic())) {
            throw new IllegalArgumentException("Dead Letter Topic specified, however max retries is set to infinity");
        }

        if (!isEmpty(functionConfig.getJar()) && !Utils.isFunctionPackageUrlSupported(functionConfig.getJar())
                && functionConfig.getJar().startsWith(BUILTIN)) {
            if (!new File(functionConfig.getJar()).exists()) {
                throw new IllegalArgumentException("The supplied jar file does not exist");
            }
        }
        if (!isEmpty(functionConfig.getPy()) && !Utils.isFunctionPackageUrlSupported(functionConfig.getPy())
                && functionConfig.getPy().startsWith(BUILTIN)) {
            if (!new File(functionConfig.getPy()).exists()) {
                throw new IllegalArgumentException("The supplied python file does not exist");
            }
        }
    }

    private static Collection<String> collectAllInputTopics(FunctionConfig functionConfig) {
        List<String> retval = new LinkedList<>();
        if (functionConfig.getInputs() != null) {
            retval.addAll(functionConfig.getInputs());
        }
        if (functionConfig.getTopicsPattern() != null) {
            retval.add(functionConfig.getTopicsPattern());
        }
        if (functionConfig.getCustomSerdeInputs() != null) {
            retval.addAll(functionConfig.getCustomSerdeInputs().keySet());
        }
        if (functionConfig.getCustomSchemaInputs() != null) {
            retval.addAll(functionConfig.getCustomSchemaInputs().keySet());
        }
        if (functionConfig.getInputSpecs() != null) {
            retval.addAll(functionConfig.getInputSpecs().keySet());
        }
        return retval;
    }

    public static ClassLoader validate(FunctionConfig functionConfig, String functionPkgUrl, File uploadedInputStreamAsFile) {
        doCommonChecks(functionConfig);
        if (functionConfig.getRuntime() == FunctionConfig.Runtime.JAVA) {
            ClassLoader classLoader = null;
            if (org.apache.commons.lang3.StringUtils.isNotBlank(functionPkgUrl)) {
                try {
                    classLoader = Utils.extractClassLoader(functionPkgUrl);
                } catch (Exception e) {
                    throw new IllegalArgumentException("Corrupted Jar File", e);
                }
            } else if (uploadedInputStreamAsFile != null) {
                try {
                    classLoader = loadJar(uploadedInputStreamAsFile);
                } catch (MalformedURLException e) {
                    throw new IllegalArgumentException("Corrupted Jar File", e);
                }
            } else if (!isEmpty(functionConfig.getJar())) {
                File jarFile = new File(functionConfig.getJar());
                if (!jarFile.exists()) {
                    throw new IllegalArgumentException("Jar file does not exist");
                }
                try {
                    classLoader = loadJar(jarFile);
                } catch (Exception e) {
                    throw new IllegalArgumentException("Corrupted Jar File", e);
                }
            } else {
                throw new IllegalArgumentException("Function Package is not provided");
            }
            doJavaChecks(functionConfig, classLoader);
            return classLoader;
        } else {
            doPythonChecks(functionConfig);
            return null;
        }
    }
}
