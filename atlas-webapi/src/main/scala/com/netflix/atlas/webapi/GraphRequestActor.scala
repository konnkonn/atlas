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
package com.netflix.atlas.webapi

import java.awt.Color
import java.io.ByteArrayOutputStream
import java.time.Duration

import akka.actor.Actor
import akka.actor.ActorLogging
import akka.actor.ActorRef
import com.netflix.atlas.akka.DiagnosticMessage
import com.netflix.atlas.chart._
import com.netflix.atlas.chart.model._
import com.netflix.atlas.core.model._
import com.netflix.atlas.core.stacklang.Interpreter
import com.netflix.atlas.core.util.PngImage
import com.netflix.atlas.core.util.Strings
import com.netflix.atlas.json.Json
import com.netflix.spectator.api.Spectator
import spray.can.Http
import spray.http.MediaTypes._
import spray.http._


class GraphRequestActor extends Actor with ActorLogging {

  import com.netflix.atlas.webapi.GraphApi._

  private val errorId = Spectator.registry().createId("atlas.graph.errorImages")

  private val dbRef = context.actorSelection("/user/db")

  private var request: Request = _
  private var responseRef: ActorRef = _

  def receive = {
    case v => try innerReceive(v) catch {
      case t: Exception if request != null && request.shouldOutputImage =>
        // When viewing a page in a browser an error response is not rendered. To make it more
        // clear to the user we return a 200 with the error information encoded into an image.
        sendErrorImage(t, request.flags.width, request.flags.height, sender())
      case t: Throwable =>
        DiagnosticMessage.handleException(sender())(t)
    }
  }

  def innerReceive: Receive = {
    case req: Request =>
      request = req
      responseRef = sender()
      dbRef.tell(req.toDbRequest, self)
    case DataResponse(data) => sendImage(data)
    case ev: Http.ConnectionClosed =>
      log.info("connection closed")
      context.stop(self)
  }

  private def sendErrorImage(t: Throwable, w: Int, h: Int, responder: ActorRef) {
    val simpleName = t.getClass.getSimpleName
    Spectator.registry().counter(errorId.withTag("error", simpleName)).increment()
    val msg = s"$simpleName: ${t.getMessage}"
    val image = HttpEntity(`image/png`, PngImage.error(msg, w, h).toByteArray)
    responder ! HttpResponse(status = StatusCodes.OK, entity = image)
  }

  private def sendImage(data: Map[DataExpr, List[TimeSeries]]): Unit = {
    val plotExprs = request.exprs.groupBy(_.axis.getOrElse(0))
    val multiY = plotExprs.size > 1

    val palette = Palette.fromResource(request.flags.palette).iterator
    val shiftPalette = Palette.fromResource("bw").iterator

    val plots = plotExprs.toList.sortWith(_._1 < _._1).map { case (yaxis, exprs) =>

      val axisCfg = request.flags.axes(yaxis)
      val dfltStyle = if (axisCfg.stack) LineStyle.STACK else LineStyle.LINE
      var axisColor: Option[Color] = None

      val lines = exprs.flatMap { s =>
        val ts = s.expr.eval(request.evalContext, data).data

        val tmp = ts.map { t =>
          val color = s.color.getOrElse {
            axisColor.getOrElse {
              val c = if (s.offset > 0L) shiftPalette.next() else palette.next()
              // Alpha setting if present will set the alpha value for the color automatically
              // assigned by the palette. If using an explicit color it will have no effect as the
              // alpha can be set directly using an ARGB hex format for the color.
              s.alpha.fold(c)(a => Colors.withAlpha(c, a))
            }
          }
          if (multiY) axisColor = Some(color)

          val offset = Strings.toString(Duration.ofMillis(s.offset))
          val newT = t.withTags(t.tags + (TagKey.offset -> offset))
          LineDef(
            data = newT.withLabel(s.legend(newT)),
            color = color,
            lineStyle = s.lineStyle.fold(dfltStyle)(s => LineStyle.valueOf(s.toUpperCase)),
            lineWidth = s.lineWidth)
        }
        tmp.sortWith(_.data.label < _.data.label)
      }

      PlotDef(lines)
    }

    val graphDef = request.newGraphDef(plots)

    val baos = new ByteArrayOutputStream
    request.engine.write(graphDef, baos)

    val entity = HttpEntity(request.contentType, baos.toByteArray)
    responseRef ! HttpResponse(StatusCodes.OK, entity)
    context.stop(self)
  }
}

