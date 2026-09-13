/*
 * Copyright © 2024-2025 Apple Inc. and the Pkl project authors. All rights reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.pkl.commons.cli.svm;

import com.oracle.svm.core.annotate.Alias;
import com.oracle.svm.core.annotate.RecomputeFieldValue;
import com.oracle.svm.core.annotate.RecomputeFieldValue.Kind;
import com.oracle.svm.core.annotate.TargetClass;
import java.util.Map;

/** Removes build-machine thread state from Pkl's statically initialized polyglot context. */
@SuppressWarnings({"unused", "ClassName"})
@TargetClass(className = "com.oracle.truffle.polyglot.PolyglotContextImpl")
public final class PolyglotContextImplTarget {
  @Alias
  @RecomputeFieldValue(kind = Kind.NewInstance, declClassName = "java.util.HashMap")
  public Map<?, ?> threads;

  @Alias @RecomputeFieldValue(kind = Kind.Reset)
  public WeakAssumedValueTarget singleThreadValue;

  @Alias @RecomputeFieldValue(kind = Kind.Reset)
  public PolyglotThreadInfoTarget cachedThreadInfo;
}
