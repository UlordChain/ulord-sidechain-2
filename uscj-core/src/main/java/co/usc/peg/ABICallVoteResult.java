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

package co.usc.peg;

/**
 * Immutable representation of the result of a vote
 * on a given ABI function call.
 * Can either be successful or failed.
 * Both successful and failed vote results
 * can carry an associated result.
 * @author Ariel Mendelzon
 */
public final class ABICallVoteResult {
    private boolean successful;
    private Object result;

    public ABICallVoteResult(boolean successful, Object result) {
        this.successful = successful;
        this.result = result;
    }

    public boolean wasSuccessful() {
        return successful;
    }

    public Object getResult() {
        return result;
    }
}