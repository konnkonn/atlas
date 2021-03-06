/*
 * Copyright 2015 Netflix, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.netflix.atlas.chart.graphics

import java.awt.Color
import java.awt.Graphics2D

import com.netflix.atlas.chart.model.LineDef
import com.netflix.atlas.core.util.UnitPrefix

/**
 * Draws a legend entry for a line.
 *
 * @param line
 *     Definition for the line.
 * @param showStats
 *     If true then summary stats will be shown below the label for the line.
 */
case class LegendEntry(line: LineDef, showStats: Boolean) extends Element with FixedHeight {
  override def height: Int = {
    if (!showStats) Constants.normalFontDims.height else {
      Constants.normalFontDims.height + Constants.smallFontDims.height * 3
    }
  }

  override def draw(g: Graphics2D, x1: Int, y1: Int, x2: Int, y2: Int): Unit = {
    val d = Constants.normalFontDims.height - 4

    // Draw the color box for the legend entry. If the color has an alpha setting, then the
    // background can impact the color so we first fill with white to make it the same as the
    // background color of the chart.
    g.setColor(Color.WHITE)
    g.fillRect(x1 + 2, y1 + 2, d, d)
    g.setColor(line.color)
    g.fillRect(x1 + 2, y1 + 2, d, d)

    // Border for the color box
    g.setColor(Color.BLACK)
    g.drawRect(x1 + 2, y1 + 2, d, d)

    // Draw the label
    val txt = Text(line.data.label, alignment = TextAlignment.LEFT)
    val truncated = txt.truncate(x2 - x1 - d - 4)
    truncated.draw(g, x1 + d + 4, y1, x2, y2)

    if (showStats) {
      val stats = line.legendStats
      val max = format(stats.max)
      val min = format(stats.min)
      val avg = format(stats.avg)
      val last = format(stats.last)
      val total = format(stats.total)
      val count = format(stats.count)
      val rows = List(
        "    Max : %s    Min  : %s".format(max, min),
        "    Avg : %s    Last : %s".format(avg, last),
        "    Tot : %s    Cnt  : %s".format(total, count)
      )
      val offset = y1 + Constants.normalFontDims.height
      val rowHeight = Constants.smallFontDims.height
      rows.zipWithIndex.foreach { case (row, i) =>
        val txt = Text(row, font = Constants.smallFont, alignment = TextAlignment.LEFT)
        txt.draw(g, x1 + d + 4, offset + i * rowHeight, x2, y2)
      }

    }
  }

  private def format(v: Double): String = UnitPrefix.format(v, "%9.3f%1s")
}

