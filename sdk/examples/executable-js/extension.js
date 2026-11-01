// Independently authored pure-JavaScript example. Packing and validation never run this file.
// Native owner approval is required before even initialization/describe.
defineExtension({
  tools: [{
    name: "reverse_text",
    description: "Reverse the supplied Unicode code points locally",
    parameters: {
      type: "object",
      properties: {text: {type: "string", maxLength: 4000}},
      required: ["text"],
      additionalProperties: false
    },
    execute(args, state) {
      if (!args || typeof args.text !== "string" || Array.from(args.text).length > 4000) {
        throw new Error("Expected text of at most4000 Unicode code points");
      }
      return {text: Array.from(args.text).reverse().join("")};
    }
  }]
});
