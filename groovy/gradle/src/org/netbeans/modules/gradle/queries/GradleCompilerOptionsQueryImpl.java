/*
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
package org.netbeans.modules.gradle.queries;

import java.beans.PropertyChangeEvent;
import java.beans.PropertyChangeListener;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.swing.event.ChangeListener;
import org.netbeans.api.annotations.common.CheckForNull;
import org.netbeans.api.annotations.common.NonNull;
import org.netbeans.modules.gradle.NbGradleProjectImpl;
import org.netbeans.spi.java.queries.*;

import org.openide.filesystems.FileObject;
import org.openide.filesystems.FileUtil;
import org.openide.util.ChangeSupport;
import org.openide.util.Exceptions;
import org.openide.util.Parameters;

/**
 * Implementation of the {@link CompilerOptionsQueryImplementation} to provide
 * build.gradle declared compiler arguments.
 *
 * @author Arunava Sinha
 */
public final class GradleCompilerOptionsQueryImpl implements CompilerOptionsQueryImplementation {

    private final AtomicReference<ResultImpl> result;
    private final NbGradleProjectImpl proj;

    public GradleCompilerOptionsQueryImpl(
            NbGradleProjectImpl proj) {
        Parameters.notNull("proj", proj);   // NOI18N
        this.proj = proj;
        this.result = new AtomicReference<>();
    }

    @CheckForNull
    @Override
    public Result getOptions(@NonNull final FileObject file) {
        ResultImpl res = result.get();
        if (res == null) {
            res = new ResultImpl(proj);
            if (!result.compareAndSet(null, res)) {
                res = result.get();
            }
            assert res != null;
        }
        return res;
    }

    private static final class ResultImpl extends Result implements PropertyChangeListener {

        private static final List<String> EMPTY = Collections.EMPTY_LIST;
        private final ChangeSupport cs;
        //@GuardedBy("this")
        private List<String> cache;

        //@GuardedBy("this")
        private final NbGradleProjectImpl proj;

        ResultImpl(NbGradleProjectImpl proj) {
            this.proj = proj;
            proj.getProjectWatcher().addPropertyChangeListener(this);
            this.cs = new ChangeSupport(this);
        }

        @Override
        public List<? extends String> getArguments() {
            List<String> args;
            synchronized (this) {
                args = cache;
            }
            if (args == null) {
                args = createArguments();
                synchronized (this) {
                    if (cache == null) {
                        cache = args;
                    } else {
                        args = cache;
                    }
                }
            }
            return args;
        }

        private List<String> createArguments() {
            File buildFile = FileUtil.toFile(proj.getProjectDirectory().getFileObject("build.gradle"));  // NOI18N
            String line = null;
            StringBuilder sb = new StringBuilder();

            if (buildFile == null || !buildFile.exists()) {
                return EMPTY;
            }

            boolean isCompileArgTagFound = false;
            BufferedReader br = null;

            try {
                br = new BufferedReader(new FileReader(buildFile));

                while ((line = br.readLine()) != null) {

                    if (!isCompileArgTagFound && line.contains("options.compilerArgs")) {
                        isCompileArgTagFound = true;
                    } else if (!isCompileArgTagFound) {
                        continue;
                    }

                    if (line.contains("=")) {
                        line = line.split("=")[1].trim();
                    } else {
                        line = line.trim();
                    }

                    if (line.endsWith("\\")) {
                        line = line.substring(0, line.length() - 1).trim();
                    }

                    sb.append(line);
                    if (line.endsWith("]")) {
                        break;
                    }

                }

            } catch (IOException ex) {

                Exceptions.printStackTrace(ex);
            } finally {
                try {
                    br.close();
                } catch (IOException ex) {
                    Exceptions.printStackTrace(ex);
                }
            }

            List<String> args = new ArrayList();
            if (sb.length() != 0) {

                String compilerArgs = sb.toString();
                Matcher m1 = Pattern.compile("\'(.*?)\'").matcher(compilerArgs);
                Matcher m2 = Pattern.compile("\"(.*?)\"").matcher(compilerArgs);

                while (m1.find()) {
                    args.add(m1.group(1));
                }
                while (m2.find()) {
                    args.add(m2.group(1));
                }

            }

            if (args.size()
                    != 0) {
                return args;
            }

            return EMPTY;
        }

        @Override
        public void addChangeListener(@NonNull final ChangeListener listener) {
            cs.addChangeListener(listener);
        }

        @Override
        public void removeChangeListener(@NonNull final ChangeListener listener) {
            cs.removeChangeListener(listener);
        }

        @Override
        public void propertyChange(@NonNull final PropertyChangeEvent evt) {

            reset();

        }

        private void reset() {
            synchronized (this) {
                cache = null;
            }
            cs.fireChange();
        }

    }
}
