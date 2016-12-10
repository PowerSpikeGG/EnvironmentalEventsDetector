#!/usr/bin/env python2

"""Application definition for the Image Fetcher API.

The Image Fetcher creates a Flask server handling several routes sending
requests to the Google Earth Engine that generates a link to download
images. All routes returns metadata containing notably the download link.

This application requires a Google Earth Engine token to work. If you do not
own one, please ask one on the official website [1]. If you do own one, you
can initialize it by running the following command:
    python2 -c "import ee; ee.Initialize()"

Defined routes are:
    /rgb
    /forestDiff
"""

import ee
import gflags
import json

from datetime import date
from dateutil.relativedelta import relativedelta
from flask import Flask
from flask import jsonify

from fetcher import ImageFetcher
from imagefetcher.utils import Error
from imagefetcher.utils import Parser
from imagefetcher.utils import get_param


app = Flask(__name__)

FLAGS = gflags.FLAGS
gflags.DEFINE_string("default_delta", "0000-03-00", "default range "
        "within images are still considered as 'close to the date'.")
gflags.DEFINE_string("default_scale", "100", "default image scale, "
        "in meter per pixels (lower is better).")
gflags.DEFINE_string("host", "0.0.0.0", "Server listening host.")
gflags.DEFINE_integer("port", 5000, "Server listening port.")
gflags.DEFINE_boolean("debug", False, "Run in debug mode.")

# Initialize the Google Earth Engine
ee.Initialize()


fetcher = ImageFetcher()


@app.errorhandler(Error)
def handle_error(error):
    """Handler triggered when the Error exception is raised."""
    response = jsonify(error.to_dict())
    response.status_code = error.status_code
    return response


@app.route('/rgb')
@get_param("date", parser=Parser.date, required=True)
@get_param("polygon", parser=Parser.polygon, required=True)
@get_param("scale", parser=float, default=100)
@get_param("delta", parser=Parser.date_delta, default=relativedelta(months=3))
def rgb_handler(date, polygon, scale, delta):
    """Generates a RGB image of an area. Images are in PNG (in a zip).

    GET query parameters:
        date (yyyy-mm-dd):
            Average date of the image to fetch. Required.
        polygon (list[list[int]]):
            Area to visualize. Required.
        scale (float):
            Precision of the picture. Unit is meter per pixels so lower is
            better.
        delta (yyyy-mm-dd):
            Delta within images are considered valid.
    Returns:
        A JSON containing metadata about the image:
            href (link):
                Link to download the image.
            error (str):
                In case of error, displays the error message.
    """
    start_date = date - delta
    end_date = date + delta
    url = fetcher.GetRGBImage(start_date, end_date, polygon, scale)
    return jsonify(href=url)


@app.route('/forestDiff')
@get_param('polygon', parser=Parser.polygon, required=True)
@get_param('start', parser=int, default=2000)
@get_param('stop', parser=int, default=date.today().year)
@get_param('scale', parser=float, default=500)
def forest_diff_handler(polygon, start, stop, scale):
    """Generates a RGB image of an are representing {de,re}forestation.

    Generates a RGB image where red green and blue channels correspond
    respectively to deforestation, reforestation and non land values. Non
    land values (blue channel) is set to 255 if the pixel is over non-land
    field (such as ocean, rivers...) and 0 elsewhere.

    See :meth:`ImageFetcher.GetForestIndicesImage` for more informations.

    GET Parameters:
        polygon (list[list[int]]):
            Area to visualize. Required.
        start (int):
            Reference year. Must be greater or equal than 2000.
        stop (int):
            Year on which we will subtract the data generated from start year.
            Must be greater than start year, and lower than current year.
        scale (float):
            Precision of the picture. Unit is meter per pixels so lower is
            better.
    Returns:
        A JSON containing metadata about the image:
            href (link):
                Link to download the image.
            error (str):
                In case of error, displays the error message.
    """
    current_year = date.today().year
    try:
        assert 2000 <= start < current_year, ("Start year must be within 2000 "
            "and %s" % (current_year - 1))
        assert start < stop <= current_year, ("Stop year must be within start "
            "and %s" % current_year)
    except AssertionError as e:
        raise Error(str(e))

    url = fetcher.GetForestIndicesImage(start, stop, polygon, scale)
    return jsonify(href=url)


@app.route("/")
def main_route():
    """Simple route useful for checking if the server is alive."""
    return ""
