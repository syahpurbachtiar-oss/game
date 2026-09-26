module.exports = {
  apps: [
    {
      name: "bokir-vps",
      script: "server.js",
      cwd: __dirname,
      autorestart: true,
      watch: false,
      max_memory_restart: "250M",
      env: { NODE_ENV: "production" }
    }
  ]
};
