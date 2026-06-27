import "./App.css";
import Home from "./pages/Home";
import { getCurrentWindow } from '@tauri-apps/api/window';
import { Menu } from '@tauri-apps/api/menu';

// const menu = await Menu.new({
//   items: [
//     {
//       id: 'file',
//       text: 'File',
//       action: () => {
//         console.log('quit pressed');
//       },
//     },
//     {
//       id: 'check_item',
//       text: 'Check Item',
//       action: () => {
//         console.log('Check pressed');
//       },
//     },
//     {
//       item: 'Separator',
//     },
//     {
//       id: 'disabled_item',
//       text: 'Disabled Item',
//       enabled: false,
//       action: () => {
//         console.log('Disabled pressed');
//       },
//     },
//     {
//       id: 'status',
//       text: 'Status: Processing...',
//       action: () => {
//         console.log('Status pressed');
//       },
//     },
//   ],
// });

// // If a window was not created with an explicit menu or had one set explicitly,
// // this menu will be assigned to it.
// menu.setAsAppMenu().then(async (res) => {
//   console.log('menu set success', res);

//   // Update individual menu item text
//   const statusItem = await menu.get('status');
//   if (statusItem) {
//     await statusItem.setText('Status: Ready');
//   }
// });

function App() {

  return (
    <>
      <Home />
    </>
  );
}

export default App;
