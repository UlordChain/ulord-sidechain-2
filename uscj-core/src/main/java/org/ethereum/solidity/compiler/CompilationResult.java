/*
 * This file is part of USC
 * Copyright (C) 2016 - 2018 USC developer team.
 * (derived from ethereumJ library, Copyright (c) 2016 <ether.camp>)
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with this program. If not, see <http://www.gnu.org/licenses/>.
 */

package org.ethereum.solidity.compiler;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.io.IOException;
import java.util.Collections;
import java.util.Map;

@JsonIgnoreProperties(ignoreUnknown = true)
public class CompilationResult {

    public Map<String, ContractMetadata> contracts;
    public String version;

    public static CompilationResult parse(String rawJson) throws IOException {
        if(rawJson == null || rawJson.isEmpty()){
            CompilationResult empty = new CompilationResult();
            empty.contracts = Collections.emptyMap();
            empty.version = "";

            return empty;
        } else {
            return new ObjectMapper().readValue(rawJson, CompilationResult.class);
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class ContractMetadata {
        public String abi;
        public String bin;
        public String solInterface;

        public String getInterface() {
            return solInterface;
        }

        public void setInterface(String solInterface) {
            this.solInterface = solInterface;
        }
    }
}
