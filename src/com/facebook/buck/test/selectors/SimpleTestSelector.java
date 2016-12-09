/*
 * Copyright 2016-present Facebook, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may
 * not use this file except in compliance with the License. You may obtain
 * a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */

package com.facebook.buck.test.selectors;

/**
 * A {@link TestDescription} will match if this selector's class-part is identical to the
 * TestDescriptions class name (same for the method name).
 */
public class SimpleTestSelector implements TestSelector {
  private final @Nullable String className;
  private final @Nullable String methodName;

  public SimpleTestSelector(@Nullable String className, @Nullable String methodName) {
    this.className = className;
    this.methodName = methodName;
  }

  @Override
  public String getRawSelector() {
    // This method is effectively used by Buck to plumb the args passed to the '-f' option into
    // the --test-selectors option that is passed to the junit test runner.
    // The Simple selector is intended to be only used by whoever directly calls the junit runner.
    throw new UnsupportedOperationException("SimpleTestSelector does not have a raw selector.");
  }

  @Override
  public String getExplanation() {
    return String.format("%s class:%s method:%s",
        isMatchAnyClass() ? "<any>" : className,
        isMatchAnyMethod() ? "<any>" : methodName);
  }

  @Override
  public boolean isInclusive() {
    return true;
  }

  @Override
  public boolean isMatchAnyClass() {
    return className == null;
  }

  @Override
  public boolean isMatchAnyMethod() {
    return methodName == null;
  }

  @Override
  public boolean matches(TestDescription description) {
    boolean isClassMatch;
    boolean isMethodMatch;

    if (className == null) {
      isClassMatch = true;
    } else {
      isClassMatch = description.getClassName().equals(className);
    }

    if (methodName == null) {
      isMethodMatch = true;
    } else {
      isMethodMatch = description.getMethodName().equals(methodName);
    }

    return isClassMatch && isMethodMatch;
  }
}
