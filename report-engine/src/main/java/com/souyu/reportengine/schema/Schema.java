package com.souyu.reportengine.schema;

import java.util.List;
import java.util.Map;

public class Schema {

    public static final String IR_VERSION = "1.0";

    public static final List<String> ALLOWED_INLINE_MARKS = List.of(
            "bold", "italic", "underline", "strike", "code", "link",
            "color", "font", "highlight", "subscript", "superscript", "math"
    );

    public static final List<String> ALLOWED_BLOCK_TYPES = List.of(
            "heading", "paragraph", "list", "table", "blockquote",
            "engineQuote", "hr", "code", "math", "figure",
            "callout", "kpiGrid", "widget", "toc"
    );

    public static final Map<String, String> ENGINE_AGENT_TITLES = Map.of(
            "insight", "Insight Agent",
            "media", "Media Agent",
            "query", "Query Agent"
    );

    public static final String CHAPTER_JSON_SCHEMA_TEXT = """
            {
              "$schema": "https://json-schema.org/draft/2020-12/schema",
              "title": "ReportEngineChapterIR",
              "type": "object",
              "required": [
                "chapterId",
                "title",
                "anchor",
                "order",
                "blocks"
              ],
              "properties": {
                "chapterId": {
                  "type": "string"
                },
                "anchor": {
                  "type": "string"
                },
                "title": {
                  "type": "string"
                },
                "order": {
                  "type": "number"
                },
                "summary": {
                  "type": "string"
                },
                "blocks": {
                  "type": "array",
                  "items": {
                    "$ref": "#/definitions/block"
                  }
                },
                "xrefs": {
                  "type": "object"
                },
                "widgets": {
                  "type": "array",
                  "items": {
                    "type": "string"
                  }
                },
                "footnotes": {
                  "type": "array",
                  "items": {
                    "type": "object"
                  }
                },
                "errors": {
                  "type": "array",
                  "items": {
                    "type": "string"
                  }
                },
                "metadata": {
                  "type": "object"
                }
              },
              "additionalProperties": true,
              "definitions": {
                "inlineMark": {
                  "type": "object",
                  "required": [
                    "type"
                  ],
                  "properties": {
                    "type": {
                      "type": "string",
                      "enum": [
                        "bold",
                        "italic",
                        "underline",
                        "strike",
                        "code",
                        "link",
                        "color",
                        "font",
                        "highlight",
                        "subscript",
                        "superscript",
                        "math"
                      ]
                    },
                    "value": {
                      "type": [
                        "string",
                        "number",
                        "object"
                      ]
                    },
                    "href": {
                      "type": "string",
                      "format": "uri-reference"
                    },
                    "title": {
                      "type": "string"
                    },
                    "style": {
                      "type": "object"
                    }
                  },
                  "additionalProperties": true
                },
                "inlineRun": {
                  "type": "object",
                  "required": [
                    "text"
                  ],
                  "properties": {
                    "text": {
                      "type": "string"
                    },
                    "marks": {
                      "type": "array",
                      "items": {
                        "$ref": "#/definitions/inlineMark"
                      }
                    }
                  },
                  "additionalProperties": true
                },
                "block": {
                  "oneOf": [
                    {
                      "title": "HeadingBlock",
                      "type": "object",
                      "properties": {
                        "type": {
                          "const": "heading"
                        },
                        "level": {
                          "type": "integer",
                          "minimum": 1,
                          "maximum": 6
                        },
                        "text": {
                          "type": "string"
                        },
                        "anchor": {
                          "type": "string"
                        },
                        "numbering": {
                          "type": "string"
                        },
                        "subtitle": {
                          "type": "string"
                        }
                      },
                      "required": [
                        "type",
                        "level",
                        "text",
                        "anchor"
                      ],
                      "additionalProperties": true
                    },
                    {
                      "title": "ParagraphBlock",
                      "type": "object",
                      "properties": {
                        "type": {
                          "const": "paragraph"
                        },
                        "inlines": {
                          "type": "array",
                          "items": {
                            "$ref": "#/definitions/inlineRun"
                          }
                        },
                        "align": {
                          "type": "string",
                          "enum": [
                            "left",
                            "center",
                            "right",
                            "justify"
                          ]
                        }
                      },
                      "required": [
                        "type",
                        "inlines"
                      ],
                      "additionalProperties": true
                    },
                    {
                      "title": "ListBlock",
                      "type": "object",
                      "properties": {
                        "type": {
                          "const": "list"
                        },
                        "listType": {
                          "type": "string",
                          "enum": [
                            "ordered",
                            "bullet",
                            "task"
                          ]
                        },
                        "items": {
                          "type": "array",
                          "items": {
                            "type": "array",
                            "items": {
                              "$ref": "#/definitions/block"
                            }
                          }
                        }
                      },
                      "required": [
                        "type",
                        "listType",
                        "items"
                      ],
                      "additionalProperties": true
                    },
                    {
                      "title": "TableBlock",
                      "type": "object",
                      "properties": {
                        "type": {
                          "const": "table"
                        },
                        "colgroup": {
                          "type": "array",
                          "items": {
                            "type": "object"
                          }
                        },
                        "rows": {
                          "type": "array",
                          "items": {
                            "type": "object",
                            "properties": {
                              "cells": {
                                "type": "array",
                                "items": {
                                  "type": "object",
                                  "properties": {
                                    "rowspan": {
                                      "type": "integer",
                                      "minimum": 1
                                    },
                                    "colspan": {
                                      "type": "integer",
                                      "minimum": 1
                                    },
                                    "align": {
                                      "type": "string",
                                      "enum": [
                                        "left",
                                        "center",
                                        "right"
                                      ]
                                    },
                                    "blocks": {
                                      "type": "array",
                                      "items": {
                                        "$ref": "#/definitions/block"
                                      }
                                    }
                                  },
                                  "required": [
                                    "blocks"
                                  ],
                                  "additionalProperties": true
                                }
                              }
                            },
                            "required": [
                              "cells"
                            ],
                            "additionalProperties": true
                          }
                        },
                        "caption": {
                          "type": "string"
                        },
                        "zebra": {
                          "type": "boolean"
                        }
                      },
                      "required": [
                        "type",
                        "rows"
                      ],
                      "additionalProperties": true
                    },
                    {
                      "title": "BlockquoteBlock",
                      "type": "object",
                      "properties": {
                        "type": {
                          "const": "blockquote"
                        },
                        "blocks": {
                          "type": "array",
                          "items": {
                            "$ref": "#/definitions/block"
                          }
                        },
                        "variant": {
                          "type": "string"
                        }
                      },
                      "required": [
                        "type",
                        "blocks"
                      ],
                      "additionalProperties": true
                    },
                    {
                      "title": "EngineQuoteBlock",
                      "type": "object",
                      "properties": {
                        "type": {
                          "const": "engineQuote"
                        },
                        "engine": {
                          "type": "string",
                          "enum": [
                            "insight",
                            "media",
                            "query"
                          ]
                        },
                        "title": {
                          "type": "string"
                        },
                        "blocks": {
                          "type": "array",
                          "items": {
                            "$ref": "#/definitions/block"
                          }
                        }
                      },
                      "required": [
                        "type",
                        "engine",
                        "blocks",
                        "title"
                      ],
                      "allOf": [
                        {
                          "if": {
                            "properties": {
                              "engine": {
                                "const": "insight"
                              }
                            }
                          },
                          "then": {
                            "properties": {
                              "title": {
                                "const": "Insight Agent"
                              }
                            }
                          }
                        },
                        {
                          "if": {
                            "properties": {
                              "engine": {
                                "const": "media"
                              }
                            }
                          },
                          "then": {
                            "properties": {
                              "title": {
                                "const": "Media Agent"
                              }
                            }
                          }
                        },
                        {
                          "if": {
                            "properties": {
                              "engine": {
                                "const": "query"
                              }
                            }
                          },
                          "then": {
                            "properties": {
                              "title": {
                                "const": "Query Agent"
                              }
                            }
                          }
                        }
                      ],
                      "additionalProperties": true
                    },
                    {
                      "title": "HorizontalRuleBlock",
                      "type": "object",
                      "properties": {
                        "type": {
                          "const": "hr"
                        },
                        "variant": {
                          "type": "string"
                        }
                      },
                      "required": [
                        "type"
                      ],
                      "additionalProperties": true
                    },
                    {
                      "title": "CodeBlock",
                      "type": "object",
                      "properties": {
                        "type": {
                          "const": "code"
                        },
                        "lang": {
                          "type": "string"
                        },
                        "content": {
                          "type": "string"
                        },
                        "caption": {
                          "type": "string"
                        }
                      },
                      "required": [
                        "type",
                        "content"
                      ],
                      "additionalProperties": true
                    },
                    {
                      "title": "MathBlock",
                      "type": "object",
                      "properties": {
                        "type": {
                          "const": "math"
                        },
                        "latex": {
                          "type": "string"
                        },
                        "displayMode": {
                          "type": "boolean"
                        }
                      },
                      "required": [
                        "type",
                        "latex"
                      ],
                      "additionalProperties": true
                    },
                    {
                      "title": "FigureBlock",
                      "type": "object",
                      "properties": {
                        "type": {
                          "const": "figure"
                        },
                        "img": {
                          "type": "object",
                          "properties": {
                            "src": {
                              "type": "string"
                            },
                            "alt": {
                              "type": "string"
                            },
                            "width": {
                              "type": "number"
                            },
                            "height": {
                              "type": "number"
                            },
                            "srcset": {
                              "type": "string"
                            }
                          },
                          "required": [
                            "src"
                          ],
                          "additionalProperties": true
                        },
                        "caption": {
                          "type": "string"
                        },
                        "responsive": {
                          "type": "boolean"
                        }
                      },
                      "required": [
                        "type",
                        "img"
                      ],
                      "additionalProperties": true
                    },
                    {
                      "title": "CalloutBlock",
                      "type": "object",
                      "properties": {
                        "type": {
                          "const": "callout"
                        },
                        "tone": {
                          "type": "string",
                          "enum": [
                            "info",
                            "warning",
                            "success",
                            "danger"
                          ]
                        },
                        "title": {
                          "type": "string"
                        },
                        "blocks": {
                          "type": "array",
                          "items": {
                            "$ref": "#/definitions/block"
                          }
                        }
                      },
                      "required": [
                        "type",
                        "tone",
                        "blocks"
                      ],
                      "additionalProperties": true
                    },
                    {
                      "title": "KPIGridBlock",
                      "type": "object",
                      "properties": {
                        "type": {
                          "const": "kpiGrid"
                        },
                        "items": {
                          "type": "array",
                          "items": {
                            "type": "object",
                            "properties": {
                              "label": {
                                "type": "string"
                              },
                              "value": {
                                "type": "string"
                              },
                              "unit": {
                                "type": "string"
                              },
                              "delta": {
                                "type": "string"
                              },
                              "deltaTone": {
                                "type": "string",
                                "enum": [
                                  "up",
                                  "down",
                                  "neutral"
                                ]
                              }
                            },
                            "required": [
                              "label",
                              "value"
                            ],
                            "additionalProperties": true
                          }
                        },
                        "cols": {
                          "type": "integer"
                        }
                      },
                      "required": [
                        "type",
                        "items"
                      ],
                      "additionalProperties": true
                    },
                    {
                      "title": "WidgetBlock",
                      "type": "object",
                      "properties": {
                        "type": {
                          "const": "widget"
                        },
                        "widgetId": {
                          "type": "string"
                        },
                        "widgetType": {
                          "type": "string"
                        },
                        "props": {
                          "type": "object"
                        },
                        "data": {
                          "type": "object"
                        },
                        "dataRef": {
                          "type": "string"
                        }
                      },
                      "required": [
                        "type",
                        "widgetId",
                        "widgetType"
                      ],
                      "additionalProperties": true
                    },
                    {
                      "title": "TOCBlock",
                      "type": "object",
                      "properties": {
                        "type": {
                          "const": "toc"
                        },
                        "depth": {
                          "type": "integer",
                          "minimum": 1,
                          "maximum": 4
                        },
                        "autoNumbering": {
                          "type": "boolean"
                        }
                      },
                      "required": [
                        "type"
                      ],
                      "additionalProperties": true
                    }
                  ]
                }
              }
            }
            """;
}
