/*
 * This file is part of USC
 * Copyright (C) 2016 - 2018 USC developer team.
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
package co.usc.rpc.modules.eth.subscribe;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

import java.util.Objects;

@JsonFormat(shape=JsonFormat.Shape.ARRAY)
@JsonPropertyOrder({"subscription"})
public class EthSubscribeParams {

    private final EthSubscribeTypes subscription;

    @JsonCreator
    public EthSubscribeParams(
            @JsonProperty("subscription") EthSubscribeTypes subscription) {
        this.subscription = Objects.requireNonNull(subscription);
    }

    public EthSubscribeTypes getSubscription() {
        return subscription;
    }
}
